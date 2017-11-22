/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.security.InternalClient;

import java.io.IOException;
import java.util.List;

public class Phase implements ToXContentObject, Writeable {
    private static final String PHASE_COMPLETED = "PHASE COMPLETED";

    private static final Logger logger = ESLoggerFactory.getLogger(Phase.class);

    public static final ParseField AFTER_FIELD = new ParseField("after");
    public static final ParseField ACTIONS_FIELD = new ParseField("actions");

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<Phase, Tuple<String, NamedXContentRegistry>> PARSER = new ConstructingObjectParser<>(
            "phase", false, (a, c) -> new Phase(c.v1(), (TimeValue) a[0], (List<LifecycleAction>) a[1]));
    static {
        PARSER.declareField(ConstructingObjectParser.constructorArg(),
                (p, c) -> TimeValue.parseTimeValue(p.text(), AFTER_FIELD.getPreferredName()), AFTER_FIELD, ValueType.VALUE);
        PARSER.declareNamedObjects(ConstructingObjectParser.constructorArg(),
                (p, c, n) -> c.v2().parseNamedObject(LifecycleAction.class, n, p, c.v2()), v -> {
                    throw new IllegalArgumentException("ordered " + ACTIONS_FIELD.getPreferredName() + " are not supported");
                }, ACTIONS_FIELD);
    }

    public static Phase parse(XContentParser parser, Tuple<String, NamedXContentRegistry> context) {
        return PARSER.apply(parser, context);
    }

    private String name;
    private List<LifecycleAction> actions;
    private TimeValue after;

    public Phase(String name, TimeValue after, List<LifecycleAction> actions) {
        this.name = name;
        this.after = after;
        this.actions = actions;
    }

    public Phase(StreamInput in) throws IOException {
        this.name = in.readString();
        this.after = new TimeValue(in);
        this.actions = in.readNamedWriteableList(LifecycleAction.class);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        after.writeTo(out);
        out.writeNamedWriteableList(actions);
    }

    public TimeValue getAfter() {
        return after;
    }

    public String getName() {
        return name;
    }

    public List<LifecycleAction> getActions() {
        return actions;
    }

    protected void execute(IndexMetaData idxMeta, InternalClient client) {
        String currentActionName = IndexLifecycle.LIFECYCLE_TIMESERIES_ACTION_SETTING.get(idxMeta.getSettings());
        String indexName = idxMeta.getIndex().getName();
        if (Strings.isNullOrEmpty(currentActionName)) {
            String firstPhaseName;
            if (actions.isEmpty()) {
                firstPhaseName = PHASE_COMPLETED;
            } else {
                firstPhaseName = actions.get(0).getWriteableName();
            }
            client.admin().indices().prepareUpdateSettings(indexName)
                    .setSettings(Settings.builder().put(IndexLifecycle.LIFECYCLE_TIMESERIES_ACTION_SETTING.getKey(), firstPhaseName))
                    .execute(new ActionListener<UpdateSettingsResponse>() {

                        @Override
                        public void onResponse(UpdateSettingsResponse response) {
                            if (response.isAcknowledged() == false) {
                                logger.info("Successfully initialised phase [" + firstPhaseName + "] for index [" + indexName + "]");
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            logger.error("Failed to initialised phase [" + firstPhaseName + "] for index [" + indexName + "]", e);
                        }
                    });
        } else if (currentActionName.equals(PHASE_COMPLETED) == false) {
            LifecycleAction currentAction = actions.stream().filter(action -> action.getWriteableName().equals(currentActionName)).findAny()
                    .orElseThrow(() -> new IllegalStateException("Current action [" + currentActionName + "] not found in phase ["
                            + getName() + "] for index [" + indexName + "]"));
            currentAction.execute(idxMeta.getIndex(), client);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(AFTER_FIELD.getPreferredName(), after);
        builder.startObject(ACTIONS_FIELD.getPreferredName());
        for (LifecycleAction action : actions) {
            builder.field(action.getWriteableName(), action);
        }
        builder.endObject();
        builder.endObject();
        return builder;
    }

}
