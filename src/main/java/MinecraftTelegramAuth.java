package me.sxnsh1ness.telegramauth;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;

public final class MinecraftTelegramAuth extends JavaPlugin implements Listener {
    private PlayerDataManager db;
    private TelegramBot bot;
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
            new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
            getServer().getPluginManager().registerEvents(this, this);
        } catch (Exception e) {
            getLogger().severe("Помилка запуску: " + e.getMessage());
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() { if (db != null) db.close(); }

    private String color(String str) { return ChatColor.translateAlternateColorCodes('&', s); }

    private String genCode(UUID uuid) {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        linkCodes.put(code, uuid);
        getServer().getScheduler().runTaskLater(this, () -> linkCodes.remove(code), 600L);
        return code;
    }

    public void handleLink(long chatId, String code) {
        UUID uuid = linkCodes.remove(code);
        if (uuid == null) {
            bot.send(chatId, "❌ Код недійсний або минув термін.");
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
            String code = genCode(p.getUniqueId());
            p.sendMessage(color("&eРекомендуємо 2FA: надішліть боту &b/link " + code));
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
        if (p != null) p.sendMessage(c("&a✅ Вхід підтверджено! Приємної гри!"));
    }

    @EventHandler 
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (db.isRegistered(p.getUniqueId())) {
            p.sendMessage(c("&aВітаємо назад! Введіть &b/login <пароль>"));
        } else {
            p.sendMessage(c("&eЛаскаво просимо! Зареєструйтесь: &b/register <пароль> <повторити>"));
        }
    }

    @EventHandler 
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        db.setLoggedIn(u, false);
        timeouts.computeIfPresent(u, (uu, t) -> { t.cancel(); return null; });
    }

    private void restrict(Player p) {
        if (!db.isLoggedIn(p.getUniqueId())) {
            p.sendMessage(c("&cСпочатку авторизуйтесь!"));
        }
    }

    @EventHandler void onMove(PlayerMoveEvent e) { if (!db.isLoggedIn(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler void onChat(AsyncPlayerChatEvent e) { if (!db.isLoggedIn(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler void onCommand(PlayerCommandPreprocessEvent e) {
        String cmd = e.getMessage().toLowerCase();
        if (!cmd.startsWith("/login") && !cmd.startsWith("/register") && !db.isLoggedIn(e.getPlayer().getUniqueId()))
            e.setCancelled(true);
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender s, org.bukkit.command.Command cmd, String l, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (db.isLoggedIn(p.getUniqueId()) && !l.equals("2fa") && !l.equals("changepassword")) {
            p.sendMessage(c("&cВи вже авторизовані."));
            return true;
        }

        switch (l.toLowerCase()) {
            case "register" -> {
                if (a.length != 2 || !a[0].equals(a[1]) || a[0].length() < 4) {
                    p.sendMessage(c("&c/register <пароль> <повторити> (мін. 4 символи)"));
                    return true;
                }
                if (db.isRegistered(p.getUniqueId())) {
                    p.sendMessage(c("&cВи вже зареєстровані. Використовуйте /login"));
                    return true;
                }
                afterPassword(p, db.register(p, a[0]));
            }
            case "login" -> {
                if (a.length != 1) { p.sendMessage(c("&c/login <пароль>")); return true; }
                afterPassword(p, db.login(p, a[0]));
            }
            case "changepassword" -> {
                if (!db.isLoggedIn(p.getUniqueId())) { restrict(p); return true; }
                if (a.length != 3 || !a[1].equals(a[2]) || a[1].length() < 4) {
                    p.sendMessage(c("&c/changepassword <старий> <новий> <повторити>"));
                    return true;
                }
                if (db.changePassword(p.getUniqueId(), a[0], a[1])) {
                    p.sendMessage(c("&aПароль змінено!"));
                } else {
                    p.sendMessage(c("&cНевірний старий пароль."));
                }
            }
            case "2fa" -> {
                if (a.length == 0) { p.sendMessage(c("&c/2fa status [гравець] | /2fa unlink")); return true; }
                if (a[0].equalsIgnoreCase("unlink")) {
                    if (!db.isLoggedIn(p.getUniqueId())) { restrict(p); return true; }
                    db.setTelegramId(p.getUniqueId(), null);
                    p.sendMessage(c("&aTelegram відв’язаний. Тепер тільки пароль."));
                    return true;
                }
                if (a[0].equalsIgnoreCase("status")) {
                    Player t = (a.length > 1 && p.hasPermission("telegramauth.2fa.others")) ?
                            getServer().getPlayer(a[1]) : p;
                    if (t == null) { p.sendMessage(c("&cГравець не онлайн.")); return true; }
                    Long id = db.getTelegramId(t.getUniqueId());
                    p.sendMessage(c("&7=== 2FA статус: &e" + t.getName() + " &7==="));
                    p.sendMessage(id == null ? c("&c✖ Не прив’язаний") : c("&a✔ Прив’язаний (активна 2FA)"));
                    if (id == null && t == p && db.isLoggedIn(p.getUniqueId())) {
                        String code = genCode(p.getUniqueId());
                        p.sendMessage(c("&eПрив’язати: надішліть боту &b/link " + code));
                    }
                    return true;
                }
            }
        }
        return true;
    }
          }
