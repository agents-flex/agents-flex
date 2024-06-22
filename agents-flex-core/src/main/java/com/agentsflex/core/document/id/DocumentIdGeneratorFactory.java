/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.document.id;

public abstract class DocumentIdGeneratorFactory {

    private static DocumentIdGeneratorFactory factory = new DocumentIdGeneratorFactory() {
        final MD5IdGenerator randomIdGenerator = new MD5IdGenerator();

        @Override
        public DocumentIdGenerator createGenerator() {
            return randomIdGenerator;
        }
    };

    public static DocumentIdGeneratorFactory getFactory() {
        return factory;
    }

    public static void setFactory(DocumentIdGeneratorFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory can not be null");
        }
        DocumentIdGeneratorFactory.factory = factory;
    }

    public static DocumentIdGenerator getDocumentIdGenerator() {
        return factory.createGenerator();
    }


    abstract DocumentIdGenerator createGenerator();

}
