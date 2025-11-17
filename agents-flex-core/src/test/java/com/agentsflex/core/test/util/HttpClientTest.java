package com.agentsflex.core.test.util;

import com.agentsflex.core.model.client.HttpClient;

public class HttpClientTest {

    public static void main(String[] args) throws InterruptedException {
        HttpClient httpClient = new HttpClient();
        for (int i = 0; i < 10; i++){
            httpClient.get("https://agentsflex.com/");
        }
        Thread.sleep(61000);
        System.out.println("finished!!!");
    }
}
