package me.sxnsh1ness.telegramauth;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.util.*;

public final class MinecraftTelegramAuth extends JavaPlugin implements Listener {
    private PlayerDataManager db;
    private TelegramBot bot;
    private BotSession botSession;
    private final Map<UUID, BukkitTask> timeouts = new HashMap<>();
    private final Map<String, UUID> linkCodes = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getString("telegram.bot-token").contains("YOUR")) {
            getLogger().severe("Встановіть bot-token у config.yml!");
            setEnabled(false);
            return;
        }

        try {
            db = new PlayerDataManager(getDataFolder().toPath());
            bot = new TelegramBot(this);
            try {
                // Новий спосіб для версії 9.x
                TelegramBotsLongPollingApplication botsApp = new TelegramBotsLongPollingApplication();
                botSession = botsApp.registerBot(getConfig().getString("telegram.bot-token"), bot); // bot — твій екземпляр TelegramBot
                getLogger().info("Telegram бот успішно запущений!");
            } catch (Exception e) {
                getLogger().severe("Не вдалося запустити Telegram бота: " + e.getMessage());
                e.printStackTrace();
                getServer().getPluginManager().disablePlugin(this);
            }
            getServer().getPluginManager().registerEvents(this, this);
        } catch (Exception e) {
            getLogger().severe("Помилка запуску: " + e.getMessage());
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        if (db != null)
            db.close();
        if (botSession != null) botSession.stop();
    }

    private String color(String str) {
        return ChatColor.translateAlternateColorCodes('&', str);
    }

    private String generateCode(UUID uuid) {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        linkCodes.put(code, uuid);
        getServer().getScheduler().runTaskLater(this, () -> linkCodes.remove(code), 600L);
        return code;
    }

    public void handleLink(long chatId, String code) {
        UUID uuid = linkCodes.remove(code);
        if (uuid == null) {
            bot.send(chatId, "❌ Код недійсний або минув термін дії.");
            return;
        }
        db.setTelegramId(uuid, chatId);
        Player p = getServer().getPlayer(uuid);
        if (p != null) p.sendMessage(color("&aTelegram успішно прив’язаний! Тепер 2FA активна."));
        bot.send(chatId, "✅ Акаунт Minecraft прив’язаний. Тепер підтверджуйте входи кнопкою.");
    }

    private void afterPassword(Player p, boolean success) {
        if (!success) {
            p.sendMessage(color("&cНевірний пароль!"));
            return;
        }

        Long chatId = db.getTelegramId(p.getUniqueId());
        if (chatId == null) {
            db.setLoggedIn(p.getUniqueId(), true);
            p.sendMessage(color("&aВи увійшли!"));
            String code = generateCode(p.getUniqueId());
            p.sendMessage(color("&eРекомендуємо підключити 2FA: надішліть боту &b/link " + code));
        } else {
            db.setPending(p.getUniqueId(), true);
            bot.sendConfirm(chatId, p);
            p.sendMessage(color("&bПідтвердіть вхід у Telegram протягом " +
                    getConfig().getInt("2fa.timeout-seconds", 120) + " секунд!"));

            timeouts.computeIfPresent(p.getUniqueId(), (u, t) -> { t.cancel(); return null; });
            timeouts.put(p.getUniqueId(), getServer().getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && !db.isLoggedIn(p.getUniqueId())) {
                    db.setPending(p.getUniqueId(), false);
                    p.kickPlayer(color("&c⏰ Тайм-аут 2FA!\n&eСпробуйте /login ще раз."));
                }
            }, getConfig().getLong("2fa.timeout-seconds", 120) * 20L));
        }
    }

    public void confirmFromTg(UUID uuid) {
        timeouts.computeIfPresent(uuid, (u, t) -> { t.cancel(); return null; });
        db.setPending(uuid, false);
        db.setLoggedIn(uuid, true);
        Player p = getServer().getPlayer(uuid);
        if (p != null) p.sendMessage(color("&a✅ Вхід підтверджено! Приємної гри!"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (db.isRegistered(p.getUniqueId())) {
            p.sendMessage(color("&aВітаємо назад! Введіть &b/login <пароль>"));
        } else {
            p.sendMessage(color("&eЛаскаво просимо! Зареєструйтесь: &b/register <пароль> <повторити>"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        db.setLoggedIn(u, false);
        timeouts.computeIfPresent(u, (uu, t) -> {
            t.cancel(); return null;
        });
    }

    private void restrict(Player p) {
        if (!db.isLoggedIn(p.getUniqueId())) {
            p.sendMessage(color("&cСпочатку авторизуйтесь!"));
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!db.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (!db.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String cmd = e.getMessage().toLowerCase();
        if (!cmd.startsWith("/login") && !cmd.startsWith("/register") && !db.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(org.bukkit.command.@NonNull CommandSender sender, org.bukkit.command.@NonNull Command cmd, @NonNull String label, String @NonNull [] args) {
        if (!(sender instanceof Player player)) return true;
        if (db.isLoggedIn(player.getUniqueId()) && !label.equals("2fa") && !label.equals("changepassword")) {
            player.sendMessage(color("&cВи вже авторизовані."));
            return true;
        }

        switch (label.toLowerCase()) {
            case "register" -> {
                if (args.length != 2 || !args[0].equals(args[1]) || args[0].length() < 4) {
                    player.sendMessage(color("&c/register <пароль> <повторити> (мін. 4 символи)"));
                    return true;
                }
                if (db.isRegistered(player.getUniqueId())) {
                    player.sendMessage(color("&cВи вже зареєстровані. Використовуйте /login"));
                    return true;
                }
                afterPassword(player, db.register(player, args[0]));
            }

            case "login" -> {
                if (args.length != 1) {
                    player.sendMessage(color("&c/login <пароль>"));
                    return true;
                }
                afterPassword(player, db.login(player, args[0]));
            }

            case "changepassword" -> {
                if (!db.isLoggedIn(player.getUniqueId())) {
                    restrict(player);
                    return true;
                }
                if (args.length != 3 || !args[1].equals(args[2]) || args[1].length() < 4) {
                    player.sendMessage(color("&c/changepassword <старий> <новий> <повторити>"));
                    return true;
                }
                if (db.changePassword(player.getUniqueId(), args[0], args[1])) {
                    player.sendMessage(color("&aПароль змінено!"));
                } else {
                    player.sendMessage(color("&colorНевірний старий пароль."));
                }
            }

            case "2fa" -> {
                if (args.length == 0) {
                    player.sendMessage(color("&c/2fa status [гравець]"));
                    return true;
                }

                if (args[0].equalsIgnoreCase("status")) {
                    Player t = (args.length > 1 && player.hasPermission("telegramauth.2fa.others")) ? getServer().getPlayer(args[1]) : player;
                    if (t == null) {
                        player.sendMessage(color("&cГравець не онлайн."));
                        return true;
                    }
                    Long id = db.getTelegramId(t.getUniqueId());
                    player.sendMessage(color("&7=== 2FA статус: &e" + t.getName() + " &7==="));
                    player.sendMessage(id == null ? color("&c✖ Не прив’язаний") : color("&a✔ Прив’язаний (активна 2FA)"));
                    if (id == null && t == player && db.isLoggedIn(player.getUniqueId())) {
                        String code = generateCode(player.getUniqueId());
                        player.sendMessage(color("&eПрив’язати: надішліть боту &b/link " + code));
                    }
                    return true;
                }
            }
        }
        return true;
    }
}
