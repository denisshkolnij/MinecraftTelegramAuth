package me.sxnsh1ness.telegramauth;

import org.bukkit.Bukkit;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

public class TelegramBot extends TelegramLongPollingBot {
    private final TelegramAuth plugin;

    public TelegramBot(TelegramAuth plugin) { this.plugin = plugin; }

    @Override public String getBotUsername() { return plugin.getConfig().getString("telegram.bot-username"); }
    @Override public String getBotToken() { return plugin.getConfig().getString("telegram.bot-token"); }

    @Override
    public void onUpdateReceived(Update u) {
        if (!u.hasMessage() || !u.getMessage().hasText()) return;
        String text = u.getMessage().getText();
        long chatId = u.getMessage().getChatId();

        if (text.equals("/start")) {
            send(chatId, "Привіт! Надішли /link <код> для прив’язки акаунту.");
        } else if (text.startsWith("/link ")) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.handleLink(chatId, text.substring(6).trim()));
        }
    }

    public void sendConfirm(long chatId, org.bukkit.entity.Player p) {
        var btn = new InlineKeyboardButton("✅ Підтвердити вхід");
        btn.setCallbackData("confirm_" + p.getUniqueId());
        var markup = new InlineKeyboardMarkup(List.of(List.of(btn)));

        SendMessage msg = SendMessage.builder()
                .chatId(chatId)
                .text("Запит на вхід: " + p.getName())
                .replyMarkup(markup)
                .build();
        try { execute(msg); } catch (TelegramApiException ignored) {}
    }

    public void send(long chatId, String text) {
        try { execute(SendMessage.builder().chatId(chatId).text(text).build()); }
        catch (TelegramApiException ignored) {}
    }

    // Викликати з іншого місця (наприклад через webhook або callback handler)
    // У цій версії callback обробляється через окремий механізм або можна додати webhook
}
