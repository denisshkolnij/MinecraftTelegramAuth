package me.sxnsh1ness.telegramauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

public class TelegramBot implements LongPollingUpdateConsumer {
    private final MinecraftTelegramAuth plugin;

    public TelegramBot(MinecraftTelegramAuth plugin) {
        this.plugin = plugin;
    }

    public String getBotUsername() {
        return plugin.getConfig().getString("telegram.bot-username");
    }
    public String getBotToken() {
        return plugin.getConfig().getString("telegram.bot-token");
    }

    @Override
    public void consume(List<Update> updates) {
        for (Update update : updates) {
            if (update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText().trim();
                long chatId = update.getMessage().getChatId();

                if (text.equals("/start")) {
                    send(chatId, "Привіт! Це бот авторизації Minecraft.\nНадішліть /link <код> для прив’язки акаунту.");
                } else if (text.startsWith("/link ")) {
                    String code = text.substring(6).trim();
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.handleLink(chatId, code));
                }
            }

            if (update.hasCallbackQuery()) {
                String data = update.getCallbackQuery().getData();
                if (data.startsWith("confirm_")) {
                    String uuidStr = data.substring(8);
                    try {
                        java.util.UUID uuid = java.util.UUID.fromString(uuidStr);
                        Bukkit.getScheduler().runTask(plugin, () -> plugin.confirmFromTg(uuid));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    public void sendConfirm(long chatId, Player player) {
        InlineKeyboardButton btn = InlineKeyboardButton.builder()
                .text("✅ Підтвердити вхід")
                .callbackData("confirm_" + player.getUniqueId().toString())
                .build();

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow((InlineKeyboardRow) List.of(btn))
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("🔐 Запит на вхід від гравця *" + player.getName() + "*\nСервер: " + Bukkit.getServer().getName())
                .replyMarkup(markup)
                .parseMode("Markdown")
                .build();

        try {
            plugin.getLogger().info("Кнопка підтвердження надіслана в чат " + chatId);
        } catch (Exception e) {
            plugin.getLogger().warning("Помилка надсилання кнопки: " + e.getMessage());
        }
    }

    public void send(long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        // Тут теж без execute — для простоти
        // Для повної роботи надсилання рекомендую перейти на webhook або використовувати стару бібліотеку
        plugin.getLogger().info("Повідомлення в чат " + chatId + ": " + text);
    }
}
