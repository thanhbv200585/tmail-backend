/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.elasticsearch.json;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;

public record EMailer(Optional<String> name, String address, String domain) implements SerializableMessage {

    public EMailer(Optional<String> name, String address, String domain) {
        this.name = name;
        this.address = address;
        this.domain = removeTopDomain(domain);
    }

    String removeTopDomain(String s) {
        if (s == null) {
            return null;
        }
        if (s.contains(".")) {
            return s.substring(0, s.lastIndexOf('.'));
        }
        return s;
    }

    @Override
    @JsonProperty(JsonMessageConstants.EMailer.NAME)
    public Optional<String> name() {
        return name;
    }

    @Override
    @JsonProperty(JsonMessageConstants.EMailer.ADDRESS)
    public String address() {
        return address;
    }

    @Override
    @JsonProperty(JsonMessageConstants.EMailer.DOMAIN)
    public String domain() {
        return domain;
    }

    @Override
    public String serialize() {
        return Joiner.on(" ").join(name.orElse(" "), address);
    }

    @Override
    public final boolean equals(Object o) {
        if (o instanceof EMailer otherEMailer) {
            return Objects.equals(name, otherEMailer.name)
                    && Objects.equals(address, otherEMailer.address)
                    && Objects.equals(domain, otherEMailer.domain);
        }
        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("address", address)
                .add("domain", domain)
                .toString();
    }
}
