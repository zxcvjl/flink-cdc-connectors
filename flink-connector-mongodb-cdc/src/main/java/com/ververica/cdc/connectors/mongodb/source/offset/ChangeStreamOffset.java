/*
 * Copyright 2022 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mongodb.source.offset;

import com.ververica.cdc.connectors.base.source.meta.offset.Offset;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.ververica.cdc.connectors.mongodb.source.utils.MongoRecordUtils.maximumBsonTimestamp;
import static com.ververica.cdc.connectors.mongodb.source.utils.ResumeTokenUtils.decodeTimestamp;

/**
 * A structure describes a fine grained offset in a change log event including resumeToken and
 * clusterTime.
 */
public class ChangeStreamOffset extends Offset {

    private static final long serialVersionUID = 1L;

    public static final String TIMESTAMP_FIELD = "timestamp";

    public static final String RESUME_TOKEN_FIELD = "resumeToken";

    public static final ChangeStreamOffset NO_STOPPING_OFFSET =
            new ChangeStreamOffset(maximumBsonTimestamp());

    public ChangeStreamOffset(Map<String, String> offset) {
        this.offset = offset;
    }

    public ChangeStreamOffset(BsonDocument resumeToken) {
        Objects.requireNonNull(resumeToken);
        Map<String, String> offsetMap = new HashMap<>();
        offsetMap.put(TIMESTAMP_FIELD, String.valueOf(decodeTimestamp(resumeToken).getValue()));
        offsetMap.put(RESUME_TOKEN_FIELD, resumeToken.toJson());
        this.offset = offsetMap;
    }

    public ChangeStreamOffset(BsonTimestamp timestamp) {
        Objects.requireNonNull(timestamp);
        Map<String, String> offsetMap = new HashMap<>();
        offsetMap.put(TIMESTAMP_FIELD, String.valueOf(timestamp.getValue()));
        offsetMap.put(RESUME_TOKEN_FIELD, null);
        this.offset = offsetMap;
    }

    public void updatePosition(BsonDocument resumeToken) {
        Objects.requireNonNull(resumeToken);
        offset.put(TIMESTAMP_FIELD, String.valueOf(decodeTimestamp(resumeToken).getValue()));
        offset.put(RESUME_TOKEN_FIELD, resumeToken.toJson());
    }

    @Nullable
    public BsonDocument getResumeToken() {
        String resumeTokenJson = offset.get(RESUME_TOKEN_FIELD);
        return Optional.ofNullable(resumeTokenJson).map(BsonDocument::parse).orElse(null);
    }

    public BsonTimestamp getTimestamp() {
        long timestamp = Long.parseLong(offset.get(TIMESTAMP_FIELD));
        return new BsonTimestamp(timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChangeStreamOffset)) {
            return false;
        }
        ChangeStreamOffset that = (ChangeStreamOffset) o;
        return offset.equals(that.offset);
    }

    @Override
    public int compareTo(Offset offset) {
        if (offset == null) {
            return -1;
        }
        ChangeStreamOffset that = (ChangeStreamOffset) offset;
        return this.getTimestamp().compareTo(that.getTimestamp());
    }
}
