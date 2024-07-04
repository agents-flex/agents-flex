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
package com.agentsflex.core.document.loader;

import com.agentsflex.core.document.parser.AbstractStreamParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HttpDocumentLoader extends StreamDocumentLoader {

    private String url;
    private Map<String, String> headers;

    private final OkHttpClient okHttpClient;


    public HttpDocumentLoader(AbstractStreamParser documentParser, String url) {
        super(documentParser);
        this.url = url;
        this.okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build();
    }


    public HttpDocumentLoader(AbstractStreamParser documentParser, String url, Map<String, String> headers) {
        super(documentParser);
        this.url = url;
        this.headers = headers;
        this.okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build();
    }


    @Override
    public InputStream loadInputStream() {
        Request.Builder builder = new Request.Builder()
            .url(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
        }

        // get method
        Request request = builder.get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body != null) {
                return body.byteStream();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;
    }
}
