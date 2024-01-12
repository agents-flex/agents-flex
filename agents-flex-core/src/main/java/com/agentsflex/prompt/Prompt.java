package com.agentsflex.prompt;

import com.agentsflex.message.Message;
import com.agentsflex.util.Metadata;

import java.util.List;


public abstract class Prompt extends Metadata {

    public abstract List<Message> toMessages();

}
