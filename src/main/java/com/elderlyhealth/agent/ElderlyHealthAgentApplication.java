package com.elderlyhealth.agent;

import com.elderlyhealth.agent.bot.WeChatBotService;
import com.elderlyhealth.agent.bot.cli.CommandExecutor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@Slf4j
@EnableScheduling
@SpringBootApplication
@MapperScan("com.elderlyhealth.agent.mapper")
public class ElderlyHealthAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElderlyHealthAgentApplication.class, args);
    }

    @Component
    static class AppRunner implements CommandLineRunner {

        private final CommandExecutor commandExecutor;
        private final WeChatBotService weChatBotService;

        AppRunner(CommandExecutor commandExecutor, WeChatBotService weChatBotService) {
            this.commandExecutor = commandExecutor;
            this.weChatBotService = weChatBotService;
        }

        @Override
        public void run(String... args) {
            log.info("应用启动完成，启动 CLI 和微信 Bot");

            System.out.println("test");
            weChatBotService.start();
            weChatBotService.startBot("bot2");
            commandExecutor.start();
        }
    }
}
