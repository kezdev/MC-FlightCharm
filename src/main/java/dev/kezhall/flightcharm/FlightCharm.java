package dev.kezhall.flightcharm;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FlightCharm — a consumable item (right-click) that grants timed creative-style
 * flight, shown with a draining boss bar timer.
 *
 * The custom look comes from a resource pack: the item carries the
 * "flightcharm:flight_charm" item-model component (see the resourcepack/ folder).
 * Without the pack it harmlessly falls back to a Feather.
 */
public final class FlightCharm extends JavaPlugin implements Listener {

    private NamespacedKey charmKey;        // marks an ItemStack as a charm
    private NamespacedKey charmSecondsKey; // per-charm flight duration (seconds)
    private NamespacedKey remainingKey;    // stores a player's remaining seconds
    private NamespacedKey itemModelKey;    // resource-pack item model id

    // Config
    private int flightSeconds;
    private int maxSeconds;
    private int slowFallSeconds;
    private Particle flightParticle;     // foot ring while flying; null = disabled
    private Particle activateParticle;   // burst when a charm is used; null = disabled

    // Resource pack (optional custom texture)
    private boolean packEnabled;       // only true when enabled AND a url is set
    private String packUrl;
    private String packSha1;
    private boolean packRequired;
    private String packPrompt;
    // Stable id so re-sends update the same pack instead of stacking duplicates.
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("flightcharm".getBytes());

    private int particleSpin = 0;      // rotates the foot ring over time

    // Live state (authoritative while online; mirrored into player PDC for persistence)
    private final Map<UUID, Integer> remaining = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    @Override
    public void onEnable() {
        charmKey = new NamespacedKey(this, "charm");
        charmSecondsKey = new NamespacedKey(this, "charm_seconds");
        remainingKey = new NamespacedKey(this, "remaining");
        itemModelKey = new NamespacedKey("flightcharm", "flight_charm");

        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        // Restore any players already online (e.g. after a /reload).
        for (Player player : getServer().getOnlinePlayers()) {
            loadPlayer(player);
        }

        // Tick once per second: count down, maintain flight, update the UI.
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        // More frequent loop for the foot particle ring while flying.
        getServer().getScheduler().runTaskTimer(this, this::flightParticles, 20L, 3L);

        getLogger().info("FlightCharm enabled. One charm = " + formatTime(flightSeconds) + " of flight.");
    }

    @Override
    public void onDisable() {
        // Persist everyone's remaining time and clear boss bars.
        for (Player player : getServer().getOnlinePlayers()) {
            savePlayer(player);
            BossBar bar = bars.remove(player.getUniqueId());
            if (bar != null) {
                player.hideBossBar(bar);
            }
        }
    }

    // ----- Config -----------------------------------------------------------

    private void loadSettings() {
        reloadConfig();
        flightSeconds = Math.max(1, getConfig().getInt("flight-seconds", 600));
        maxSeconds = Math.max(flightSeconds, getConfig().getInt("max-seconds", 3600));
        slowFallSeconds = Math.max(0, getConfig().getInt("slow-fall-seconds", 8));
        flightParticle = parseParticle(getConfig().getString("flight-particle", "CLOUD"));
        activateParticle = parseParticle(getConfig().getString("activate-particle", "TOTEM_OF_UNDYING"));

        packUrl = getConfig().getString("resource-pack.url", "").trim();
        packSha1 = getConfig().getString("resource-pack.sha1", "").trim();
        packRequired = getConfig().getBoolean("resource-pack.required", false);
        packPrompt = getConfig().getString("resource-pack.prompt",
                "Install the FlightCharm pack to see the charm's custom texture.");
        // Only treat the pack as active if it's enabled and actually points somewhere.
        packEnabled = getConfig().getBoolean("resource-pack.enabled", false) && !packUrl.isEmpty();
    }

    private Particle parseParticle(String name) {
        if (name == null || name.isBlank()
                || name.equalsIgnoreCase("none") || name.equalsIgnoreCase("off")) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Unknown flight-particle '" + name + "', falling back to CLOUD.");
            return Particle.CLOUD;
        }
    }

    // ----- Per-second loop --------------------------------------------------

    private void tick() {
        for (Player player : getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            int secs = remaining.getOrDefault(id, 0);

            if (secs <= 0) {
                clearBar(player);
                continue;
            }

            // Pause (don't burn time) if the player can't use survival flight right now.
            if (!isManaged(player)) {
                clearBar(player);
                continue;
            }

            secs -= 1;
            if (secs <= 0) {
                expire(player);
                continue;
            }

            remaining.put(id, secs);
            player.getPersistentDataContainer().set(remainingKey, PersistentDataType.INTEGER, secs);

            player.setAllowFlight(true);
            updateUi(player, secs);
        }
    }

    private void updateUi(Player player, int secs) {
        Component label = Component.text("✈ Flight  " + formatTime(secs), NamedTextColor.AQUA);
        float progress = clamp((float) secs / (float) flightSeconds);

        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(label, progress, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(label);
            bar.progress(progress);
        }
    }

    private void expire(Player player) {
        UUID id = player.getUniqueId();
        remaining.remove(id);
        player.getPersistentDataContainer().remove(remainingKey);

        if (isManaged(player)) {
            if (player.isFlying()) {
                player.setFlying(false);
                if (slowFallSeconds > 0) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOW_FALLING, slowFallSeconds * 20, 0, false, false, true));
                }
            }
            player.setAllowFlight(false);
        }

        clearBar(player);
        player.sendMessage(Component.text("✈ Flight time has run out.", NamedTextColor.RED));
    }

    private void clearBar(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    // ----- Flight particles -------------------------------------------------

    private void flightParticles() {
        if (flightParticle == null) {
            return;
        }
        particleSpin++;
        for (Player player : getServer().getOnlinePlayers()) {
            if (remaining.getOrDefault(player.getUniqueId(), 0) > 0
                    && isManaged(player)
                    && player.isFlying()) {
                spawnFootParticles(player);
            }
        }
    }

    /** One-off celebratory burst around the player when a charm is activated. */
    private void spawnActivateBurst(Player player) {
        if (activateParticle == null) {
            return;
        }
        Location loc = player.getLocation();
        if (loc.getWorld() == null) {
            return;
        }
        loc.getWorld().spawnParticle(activateParticle, loc.add(0, 1.0, 0), 30, 0.5, 0.8, 0.5, 0.08);
    }

    /** Draws a small rotating ring of particles at the player's feet. */
    private void spawnFootParticles(Player player) {
        Location base = player.getLocation();
        if (base.getWorld() == null) {
            return;
        }
        final int points = 6;
        final double radius = 0.55;
        final double step = (Math.PI * 2) / points;
        final double spin = Math.toRadians(particleSpin * 12);
        for (int i = 0; i < points; i++) {
            double a = spin + i * step;
            double x = base.getX() + Math.cos(a) * radius;
            double z = base.getZ() + Math.sin(a) * radius;
            base.getWorld().spawnParticle(flightParticle,
                    x, base.getY() + 0.1, z, 1, 0, 0, 0, 0);
        }
    }

    private boolean isManaged(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    // ----- Join / quit persistence -----------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPlayer(event.getPlayer());
        sendResourcePack(event.getPlayer());
    }

    /** Offers the custom-texture pack to a player (stacks alongside any other packs). */
    private void sendResourcePack(Player player) {
        if (!packEnabled) {
            return;
        }
        try {
            ResourcePackInfo.Builder info = ResourcePackInfo.resourcePackInfo()
                    .id(PACK_ID)
                    .uri(URI.create(packUrl));
            if (!packSha1.isEmpty()) {
                info.hash(packSha1.toLowerCase());
            }
            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(info.build())
                    .required(packRequired)
                    .replace(false) // stack with the server pack / other plugins
                    .prompt(Component.text(packPrompt))
                    .build();
            player.sendResourcePacks(request);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid resource-pack url '" + packUrl + "': " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        savePlayer(player);
        remaining.remove(player.getUniqueId());
        bars.remove(player.getUniqueId());
    }

    private void loadPlayer(Player player) {
        Integer saved = player.getPersistentDataContainer().get(remainingKey, PersistentDataType.INTEGER);
        if (saved != null && saved > 0) {
            remaining.put(player.getUniqueId(), saved);
            if (isManaged(player)) {
                player.setAllowFlight(true);
            }
        }
    }

    private void savePlayer(Player player) {
        Integer secs = remaining.get(player.getUniqueId());
        if (secs != null && secs > 0) {
            player.getPersistentDataContainer().set(remainingKey, PersistentDataType.INTEGER, secs);
        } else {
            player.getPersistentDataContainer().remove(remainingKey);
        }
    }

    // ----- Using a charm ----------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // only react to the main hand (event fires for both)
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!isCharm(inHand)) {
            return;
        }

        event.setCancelled(true); // never let it behave like a normal feather

        if (!player.hasPermission("flightcharm.use")) {
            player.sendActionBar(Component.text("You don't have permission to use Flight Charms.",
                    NamedTextColor.RED));
            return; // don't consume the charm
        }

        if (!isManaged(player)) {
            player.sendActionBar(Component.text("You can already fly here.", NamedTextColor.YELLOW));
            return; // don't waste a charm in creative/spectator
        }

        // How much time this particular charm grants (falls back to the config default).
        int grant = charmSeconds(inHand);

        // Consume one charm.
        int newAmount = inHand.getAmount() - 1;
        inHand.setAmount(Math.max(newAmount, 0));
        player.getInventory().setItemInMainHand(newAmount <= 0 ? null : inHand);

        // Add flight time (capped).
        UUID id = player.getUniqueId();
        int next = Math.min(maxSeconds, remaining.getOrDefault(id, 0) + grant);
        remaining.put(id, next);
        player.getPersistentDataContainer().set(remainingKey, PersistentDataType.INTEGER, next);
        player.setAllowFlight(true);
        spawnActivateBurst(player);

        player.sendMessage(Component.text("✈ Flight charm activated! You now have "
                + formatTime(next) + " of flight.", NamedTextColor.AQUA));
    }

    private boolean isCharm(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(charmKey, PersistentDataType.BYTE);
    }

    /** The flight duration baked into a charm, or the config default for older/plain charms. */
    private int charmSeconds(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            Integer s = item.getItemMeta().getPersistentDataContainer()
                    .get(charmSecondsKey, PersistentDataType.INTEGER);
            if (s != null && s > 0) {
                return s;
            }
        }
        return flightSeconds;
    }

    private ItemStack createCharm(int amount, int seconds) {
        int secs = seconds > 0 ? seconds : flightSeconds;
        ItemStack item = new ItemStack(Material.FEATHER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Flight Charm", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to gain flight.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Grants " + formatTime(secs) + " of flight time.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        // Only apply the custom item-model when the pack is configured; otherwise the
        // charm stays a plain Feather so players without the pack never see missing-texture.
        if (packEnabled) {
            meta.setItemModel(itemModelKey);
        }
        meta.setEnchantmentGlintOverride(Boolean.TRUE);  // subtle shimmer
        meta.getPersistentDataContainer().set(charmKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(charmSecondsKey, PersistentDataType.INTEGER, secs);
        item.setItemMeta(meta);
        return item;
    }

    // ----- Commands ---------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("flightcharm")) {
            return false;
        }

        String sub = args.length >= 1 ? args[0].toLowerCase() : "time";

        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("flightcharm.reload")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                loadSettings();
                sender.sendMessage(Component.text("FlightCharm config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            case "give" -> {
                if (!sender.hasPermission("flightcharm.give")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                // /flightcharm give [player] [minutes] [amount]
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage(Component.text("Usage: /flightcharm give <player> [minutes] [amount]", NamedTextColor.RED));
                    return true;
                }
                int seconds = flightSeconds;
                if (args.length >= 3) {
                    try {
                        seconds = Math.max(1, (int) Math.round(Double.parseDouble(args[2]) * 60));
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage(Component.text("Minutes must be a number.", NamedTextColor.RED));
                        return true;
                    }
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[3]));
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage(Component.text("Amount must be a number.", NamedTextColor.RED));
                        return true;
                    }
                }
                target.getInventory().addItem(createCharm(amount, seconds));
                sender.sendMessage(Component.text("Gave " + amount + " Flight Charm(s) ("
                        + formatTime(seconds) + " each) to " + target.getName() + ".", NamedTextColor.GREEN));
                return true;
            }
            case "time" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Only players have flight time.", NamedTextColor.RED));
                    return true;
                }
                int secs = remaining.getOrDefault(p.getUniqueId(), 0);
                if (secs > 0) {
                    p.sendMessage(Component.text("✈ Flight time remaining: " + formatTime(secs),
                            NamedTextColor.AQUA));
                } else {
                    p.sendMessage(Component.text("You have no flight time. Use a Flight Charm!",
                            NamedTextColor.GRAY));
                }
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /flightcharm <give|time|reload> [player] [minutes] [amount]",
                        NamedTextColor.YELLOW));
                return true;
            }
        }
    }

    // ----- Helpers ----------------------------------------------------------

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static String formatTime(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }
}
