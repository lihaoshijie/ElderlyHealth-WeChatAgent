package com.elderlyhealth.agent.bot.cli;

public interface Command {
    String getName();
    String execute(String[] args);
}
