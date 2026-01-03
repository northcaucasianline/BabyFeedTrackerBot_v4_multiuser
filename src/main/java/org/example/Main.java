package org.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            BackupService.createArchiveDir();
            BackupService.scheduleDailyArchive();
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new BabyBot());
            System.out.println("Бот запущен");
        } catch (TelegramApiException | IOException e) {
            e.printStackTrace();
        }
    }
}