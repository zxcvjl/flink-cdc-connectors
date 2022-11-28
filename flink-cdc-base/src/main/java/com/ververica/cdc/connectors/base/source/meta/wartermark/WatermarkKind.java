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

package com.ververica.cdc.connectors.base.source.meta.wartermark;

/** The watermark kind. */
public enum WatermarkKind {
    LOW,
    HIGH,
    END;

    public WatermarkKind fromString(String kindString) {
        if (LOW.name().equalsIgnoreCase(kindString)) {
            return LOW;
        } else if (HIGH.name().equalsIgnoreCase(kindString)) {
            return HIGH;
        } else {
            return END;
        }
    }
}
