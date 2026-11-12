package com.donutsellaxe;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DonutSellAxe extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Economy econ;
    private NamespacedKey sellAxeKey;
    private NamespacedKey expiryKey;
    private final Map<Material, Double> priceMap = new EnumMap<>(Material.class);
    private File pricesFile;
    private FileConfiguration pricesConfig;
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0.##");

    @Override
    public void onEnable() {
        this.sellAxeKey = new NamespacedKey(this, "is_sell_axe");
        this.expiryKey = new NamespacedKey(this, "expiry_timestamp");

        if (!setupEconomy()) {
            getLogger().severe("Vault or an Economy provider was not found! Disabling DonutSellAxe.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        loadPricesConfig();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("sellaxe") != null) {
            getCommand("sellaxe").setExecutor(this);
            getCommand("sellaxe").setTabCompleter(this);
        }

        long interval = getConfig().getLong("settings.update-interval-ticks", 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAllPlayerAxes, interval, interval);

        getLogger().info("DonutSellAxe enabled with " + priceMap.size() + " priced items loaded!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    public void loadPricesConfig() {
        pricesFile = new File(getDataFolder(), "prices.yml");
        if (!pricesFile.exists()) {
            saveResource("prices.yml", false);
        }
        pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);
        priceMap.clear();

        ConfigurationSection section = pricesConfig.getConfigurationSection("prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null) {
                    double price = section.getDouble(key, 0.0);
                    if (price > 0.0) {
                        priceMap.put(mat, price);
                    }
                }
            }
        }
    }

    public ItemStack createSellAxe(long durationSeconds) {
        String matName = getConfig().getString("axe-item.material", "NETHERITE_AXE");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.NETHERITE_AXE;

        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        long expiryTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(sellAxeKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(expiryKey, PersistentDataType.LONG, expiryTime);

        meta.setDisplayName(colorize(getConfig().getString("axe-item.name", "&#00f986&lSELL AXE")));
        meta.setUnbreakable(getConfig().getBoolean("axe-item.unbreakable", true));

        int cmd = getConfig().getInt("axe-item.custom-model-data", 0);
        if (cmd > 0) {
            meta.setCustomModelData(cmd);
        }

        if (getConfig().getBoolean("axe-item.glow", true)) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);

        updateAxeLore(meta, expiryTime - System.currentTimeMillis());
        item.setItemMeta(meta);
        return item;
    }

    private void updateAxeLore(ItemMeta meta, long remainingMillis) {
        List<String> rawLore = getConfig().getStringList("axe-item.lore");
        List<String> formattedLore = new ArrayList<>(rawLore.size());
        String formattedTime = formatDuration(Math.max(0L, remainingMillis));
        for (String line : rawLore) {
            formattedLore.add(colorize(line.replace("%time_left%", formattedTime)));
        }
        meta.setLore(formattedLore);
    }

    private boolean isSellAxe(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(sellAxeKey, PersistentDataType.BYTE);
    }

    private long getExpiry(ItemStack item) {
        if (!isSellAxe(item)) return 0L;
        Long exp = item.getItemMeta().getPersistentDataContainer().get(expiryKey, PersistentDataType.LONG);
        return exp != null ? exp : 0L;
    }

    private void tickAllPlayerAxes() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = player.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack item = inv.getItem(i);
                if (!isSellAxe(item)) continue;

                long expiry = getExpiry(item);
                long remaining = expiry - now;
                if (remaining <= 0L) {
                    inv.setItem(i, null);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    String prefix = getConfig().getString("messages.prefix", "");
                    String msg = getConfig().getString("messages.axe-expired", "&cYour Sell Axe has expired!");
                    player.sendMessage(colorize(prefix + msg));
                } else {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        updateAxeLore(meta, remaining);
                        item.setItemMeta(meta);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isSellAxe(hand)) return;

        Block block = event.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return;
        }

        // Cancel the break so the chest/barrel/shulker box stays in place ("comes back" immediately)
        event.setCancelled(true);

        if (!player.hasPermission("donutsellaxe.use")) {
            player.sendMessage(colorize(getConfig().getString("messages.prefix", "")
                    + getConfig().getString("messages.no-permission", "&cNo permission.")));
            return;
        }

        long now = System.currentTimeMillis();
        long expiry = getExpiry(hand);
        if (expiry - now <= 0L) {
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            player.sendMessage(colorize(getConfig().getString("messages.prefix", "")
                    + getConfig().getString("messages.axe-expired", "&cYour Sell Axe has expired!")));
            return;
        }

        // For Chests (including double chests), getInventory() returns the full inventory (both halves if double chest).
        // For ShulkerBoxes or non-placed snapshots, use getInventory().
        Inventory inv = container.getInventory();
        boolean sellInsideShulkers = getConfig().getBoolean("settings.sell-shulkers-inside-chests", true);
        boolean sellEmptyShulkerItem = getConfig().getBoolean("settings.sell-empty-shulker-box-item", false);

        double totalEarned = 0.0;
        int totalItemsSold = 0;

        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;

            // Check if this item is a Shulker Box containing items
            if (sellInsideShulkers && item.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox shulker) {
                Inventory shulkerInv = shulker.getInventory();
                boolean shulkerModified = false;

                for (int sSlot = 0; sSlot < shulkerInv.getSize(); sSlot++) {
                    ItemStack sItem = shulkerInv.getItem(sSlot);
                    if (sItem == null || sItem.getType() == Material.AIR) continue;
                    if (isSellAxe(sItem)) continue;

                    Double unitPrice = priceMap.get(sItem.getType());
                    if (unitPrice != null && unitPrice > 0.0) {
                        int count = sItem.getAmount();
                        totalEarned += unitPrice * count;
                        totalItemsSold += count;
                        shulkerInv.setItem(sSlot, null);
                        shulkerModified = true;
                    }
                }

                if (shulkerModified) {
                    bsm.setBlockState(shulker);
                    item.setItemMeta(bsm);
                    inv.setItem(slot, item);
                }

                if (sellEmptyShulkerItem && isInventoryEmpty(shulkerInv)) {
                    Double shulkerPrice = priceMap.get(item.getType());
                    if (shulkerPrice != null && shulkerPrice > 0.0) {
                        int count = item.getAmount();
                        totalEarned += shulkerPrice * count;
                        totalItemsSold += count;
                        inv.setItem(slot, null);
                    }
                }
                continue;
            }

            if (isSellAxe(item)) continue;

            Double unitPrice = priceMap.get(item.getType());
            if (unitPrice != null && unitPrice > 0.0) {
                int count = item.getAmount();
                totalEarned += unitPrice * count;
                totalItemsSold += count;
                inv.setItem(slot, null);
            }
        }

        String prefix = getConfig().getString("messages.prefix", "&#00f986&lSELL AXE &8» ");
        if (totalItemsSold <= 0 || totalEarned <= 0.0) {
            String nothingMsg = getConfig().getString("messages.nothing-to-sell", "&cNothing to sell!");
            if (!nothingMsg.isEmpty()) {
                player.sendMessage(colorize(prefix + nothingMsg));
            }
            return;
        }

        econ.depositPlayer(player, totalEarned);
        String formattedTotal = MONEY_FORMAT.format(totalEarned);
        String formattedCount = String.valueOf(totalItemsSold);

        String chatMsg = getConfig().getString("messages.sold-chat", "");
        if (!chatMsg.isEmpty()) {
            player.sendMessage(colorize(prefix + chatMsg
                    .replace("%total%", formattedTotal)
                    .replace("%amount%", formattedCount)));
        }

        String actionbarMsg = getConfig().getString("messages.sold-actionbar", "");
        if (!actionbarMsg.isEmpty()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(colorize(actionbarMsg
                            .replace("%total%", formattedTotal)
                            .replace("%amount%", formattedCount))));
        }

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);

        // Update lore immediately on use
        ItemMeta handMeta = hand.getItemMeta();
        if (handMeta != null) {
            updateAxeLore(handMeta, expiry - System.currentTimeMillis());
            hand.setItemMeta(handMeta);
        }
    }

    private boolean isInventoryEmpty(Inventory inv) {
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    public String colorize(String message) {
        if (message == null) return "";
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = getConfig().getString("messages.prefix", "&#00f986&lSELL AXE &8» ");
        if (!sender.hasPermission("donutsellaxe.admin")) {
            sender.sendMessage(colorize(prefix + getConfig().getString("messages.no-permission", "&cNo permission.")));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadPricesConfig();
            sender.sendMessage(colorize(prefix + getConfig().getString("messages.reloaded", "&aReloaded!")));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(colorize(prefix + "&cPlayer not found!"));
                return true;
            }

            long durationSeconds = getConfig().getLong("settings.duration-seconds", 604800L);
            if (args.length >= 3) {
                try {
                    durationSeconds = Long.parseLong(args[2]);
                } catch (NumberFormatException ignored) {}
            }

            ItemStack axe = createSellAxe(durationSeconds);
            target.getInventory().addItem(axe).values().forEach(leftover ->
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover));

            sender.sendMessage(colorize(prefix + getConfig().getString("messages.axe-given", "&aGave Sell Axe to %player%")
                    .replace("%player%", target.getName())));
            target.sendMessage(colorize(prefix + getConfig().getString("messages.axe-received", "&aYou received a Sell Axe!")));
            return true;
        }

        sender.sendMessage(colorize(prefix + "&fUsage: &a/" + label + " give <player> [durationSeconds] &7| &a/" + label + " reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("donutsellaxe.admin")) return Collections.emptyList();
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            list.add("give");
            list.add("reload");
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return names;
        }
        return Collections.emptyList();
    }
}
