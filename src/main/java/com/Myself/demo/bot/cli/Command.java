package com.Myself.demo.bot.cli;

public interface Command {
    String getName();
    String execute(String[] args);
}
