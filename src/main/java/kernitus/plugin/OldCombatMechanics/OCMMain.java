/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics;

import kernitus.plugin.OldCombatMechanics.commands.OCMCommandCompleter;
import kernitus.plugin.OldCombatMechanics.commands.OCMCommandHandler;
import kernitus.plugin.OldCombatMechanics.hooks.PlaceholderAPIHook;
import kernitus.plugin.OldCombatMechanics.hooks.api.Hook;
import kernitus.plugin.OldCombatMechanics.module.*;
import kernitus.plugin.OldCombatMechanics.updater.ModuleUpdateChecker;
import kernitus.plugin.OldCombatMechanics.utilities.Config;
import kernitus.plugin.OldCombatMechanics.utilities.Messenger;
import kernitus.plugin.OldCombatMechanics.utilities.damage.AttackCooldownTracker;
import kernitus.plugin.OldCombatMechanics.utilities.damage.EntityDamageByEntityListener;
import kernitus.plugin.OldCombatMechanics.utilities.reflection.Reflector;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimpleBarChart;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventException;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class OCMMain extends JavaPlugin {

    private static OCMMain INSTANCE;
    private static Object GLOBAL_REGION_SCHEDULER = null;
    private final Logger logger = getLogger();
    private final OCMConfigHandler CH = new OCMConfigHandler(this);
    private final List<Runnable> disableListeners = new ArrayList<>();
    private final List<Runnable> enableListeners = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();

    public OCMMain() {
        super();
    }

    public static OCMMain getInstance() {
        return INSTANCE;
    }

    public static String getVersion() {
        return INSTANCE.getDescription().getVersion();
    }

    @Override
    public void onEnable() {
        INSTANCE = this;

        PluginDescriptionFile pdfFile = this.getDescription();

        // Setting up config.yml
        CH.setupConfigIfNotPresent();

        // Initialise ModuleLoader utility
        ModuleLoader.initialise(this);

        // Register all the modules
        registerModules();

        // Register all hooks for integrating with other plugins
        registerHooks();

        // Initialise all the hooks
        hooks.forEach(hook -> hook.init(this));

        // Set up the command handler
        getCommand("OldCombatMechanics").setExecutor(new OCMCommandHandler(this, this.getFile()));
        // Set up command tab completer
        getCommand("OldCombatMechanics").setTabCompleter(new OCMCommandCompleter());

        // Initialise the Messenger utility
        Messenger.initialise(this);

        // Initialise Config utility
        Config.initialise(this);

        // BStats Metrics
        Metrics metrics = new Metrics(this, 53);

        // Simple bar chart
        metrics.addCustomChart(
                new SimpleBarChart(
                        "enabled_modules",
                        () -> ModuleLoader.getModules().stream()
                                .filter(OCMModule::isEnabled)
                                .collect(Collectors.toMap(OCMModule::toString, module -> 1))
                )
        );

        // Pie chart of enabled/disabled for each module
        ModuleLoader.getModules().forEach(module -> metrics.addCustomChart(
                new SimplePie(module.getModuleName() + "_pie",
                        () -> module.isEnabled() ? "enabled" : "disabled"
                )));

        enableListeners.forEach(Runnable::run);

        // Properly handle Plugman load/unload.
        List<RegisteredListener> joinListeners = Arrays.stream(PlayerJoinEvent.getHandlerList().getRegisteredListeners())
                .filter(registeredListener -> registeredListener.getPlugin().equals(this))
                .collect(Collectors.toList());

        Bukkit.getOnlinePlayers().forEach(player -> {
            PlayerJoinEvent event = new PlayerJoinEvent(player, "");

            // Trick all the modules into thinking the player just joined in case the plugin was loaded with Plugman.
            // This way attack speeds, item modifications, etc. will be applied immediately instead of after a re-log.
            joinListeners.forEach(registeredListener -> {
                try {
                    registeredListener.callEvent(event);
                } catch (EventException e) {
                    e.printStackTrace();
                }
            });
        });

        // Logging to console the enabling of OCM
        logger.info(pdfFile.getName() + " v" + pdfFile.getVersion() + " has been enabled");

        if (Config.moduleEnabled("update-checker"))
            Bukkit.getScheduler().runTaskLaterAsynchronously(this,
                    () -> new UpdateChecker(this).performUpdate(), 20L);

        metrics.addCustomChart(new SimplePie("auto_update_pie",
                () -> Config.moduleSettingEnabled("update-checker",
                        "auto-update") ? "enabled" : "disabled"));

    }

    @Override
    public void onDisable() {
        final PluginDescriptionFile pdfFile = this.getDescription();

        disableListeners.forEach(Runnable::run);

        // Properly handle Plugman load/unload.
        List<RegisteredListener> quitListeners = Arrays.stream(PlayerQuitEvent.getHandlerList().getRegisteredListeners())
                .filter(registeredListener -> registeredListener.getPlugin().equals(this))
                .collect(Collectors.toList());

        // Trick all the modules into thinking the player just quit in case the plugin was unloaded with Plugman.
        // This way attack speeds, item modifications, etc. will be restored immediately instead of after a disconnect.
        Bukkit.getOnlinePlayers().forEach(player -> {
            PlayerQuitEvent event = new PlayerQuitEvent(player, "");

            quitListeners.forEach(registeredListener -> {
                try {
                    registeredListener.callEvent(event);
                } catch (EventException e) {
                    e.printStackTrace();
                }
            });
        });

        // Logging to console the disabling of OCM
        logger.info(pdfFile.getName() + " v" + pdfFile.getVersion() + " has been disabled");
    }

    private void registerModules() {
        // Update Checker (also a module so we can use the dynamic registering/unregistering)
        ModuleLoader.addModule(new ModuleUpdateChecker(this));

        // Module listeners
        ModuleLoader.addModule(new ModuleAttackCooldown(this));
        ModuleLoader.addModule(new ModulePlayerCollisions(this));

        // If below 1.16, we need to keep track of player attack cooldown ourselves
        if (Reflector.getMethod(HumanEntity.class, "getAttackCooldown", 0) == null) {
            ModuleLoader.addModule(new AttackCooldownTracker(this));
        }

        //Listeners registered after with same priority appear to be called later

        //These four listen to OCMEntityDamageByEntityEvent:
        ModuleLoader.addModule(new ModuleOldToolDamage(this));
        ModuleLoader.addModule(new ModuleSwordSweep(this));
        ModuleLoader.addModule(new ModuleOldPotionEffects(this));
        ModuleLoader.addModule(new ModuleOldCriticalHits(this));

        //Next block are all on LOWEST priority, so will be called in the following order:
        //Damage order: base -> potion effects -> critical hit -> enchantments -> blocking -> armour effects
        //EntityDamageByEntityListener calls OCMEntityDamageByEntityEvent, see modules above
        ModuleLoader.addModule(new EntityDamageByEntityListener(this));
        //Then ModuleSwordBlocking to calculate blocking
        ModuleLoader.addModule(new ModuleShieldDamageReduction(this));
        //Then OldArmourStrength to recalculate armour defense accordingly
        ModuleLoader.addModule(new ModuleOldArmourStrength(this));

        ModuleLoader.addModule(new ModuleSwordBlocking(this));
        ModuleLoader.addModule(new ModuleOldArmourDurability(this));

        ModuleLoader.addModule(new ModuleGoldenApple(this));
        ModuleLoader.addModule(new ModuleFishingKnockback(this));
        ModuleLoader.addModule(new ModulePlayerKnockback(this));
        ModuleLoader.addModule(new ModulePlayerRegen(this));

        ModuleLoader.addModule(new ModuleDisableCrafting(this));
        ModuleLoader.addModule(new ModuleDisableOffHand(this));
        ModuleLoader.addModule(new ModuleOldBrewingStand(this));
        ModuleLoader.addModule(new ModuleDisableElytra(this));
        ModuleLoader.addModule(new ModuleDisableProjectileRandomness(this));
        ModuleLoader.addModule(new ModuleDisableBowBoost(this));
        ModuleLoader.addModule(new ModuleProjectileKnockback(this));
        ModuleLoader.addModule(new ModuleNoLapisEnchantments(this));
        ModuleLoader.addModule(new ModuleDisableEnderpearlCooldown(this));
        ModuleLoader.addModule(new ModuleChorusFruit(this));

        ModuleLoader.addModule(new ModuleAttackSounds(this));
        ModuleLoader.addModule(new ModuleOldBurnDelay(this));
        ModuleLoader.addModule(new ModuleAttackFrequency(this));
        ModuleLoader.addModule(new ModuleFishingRodVelocity(this));
    }

    private void registerHooks() {
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            hooks.add(new PlaceholderAPIHook());
        }
    }

    public void upgradeConfig() {
        CH.upgradeConfig();
    }

    public boolean doesConfigExist() {
        return CH.doesConfigExist();
    }

    /**
     * Registers a runnable to run when the plugin gets disabled.
     *
     * @param action the {@link Runnable} to run when the plugin gets disabled
     */
    public void addDisableListener(Runnable action) {
        disableListeners.add(action);
    }

    /**
     * Registers a runnable to run when the plugin gets enabled.
     *
     * @param action the {@link Runnable} to run when the plugin gets enabled
     */
    public void addEnableListener(Runnable action) {
        enableListeners.add(action);
    }

    /**
     * Get the plugin's JAR file
     *
     * @return The File object corresponding to this plugin
     */
    @NotNull
    @Override
    public File getFile() {
        return super.getFile();
    }

    //Folia Scheduler (Borrowed from ArmorStandEditor)
    public static void runTaskLater(Plugin plugin, Runnable runnable, long delayedTicks) {
    	Object globalRegionScheduler = getGlobalRegionScheduler();
        callMethod(globalRegionScheduler, "runDelayed", new Class[]{Plugin.class, Consumer.class, long.class},plugin, (Consumer<?>) (task) -> runnable.run(), delayedTicks);
    }
    
    public static void runTaskTimer(Plugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
    	Object globalRegionScheduler = getGlobalRegionScheduler();
        callMethod(globalRegionScheduler, "runAtFixedRate", new Class[]{Plugin.class, Consumer.class, long.class, long.class},plugin, (Consumer<?>) (task) -> runnable.run(), initialDelayTicks, periodTicks);
    }
    
    public static Object getGlobalRegionScheduler() {
        if (GLOBAL_REGION_SCHEDULER == null) {
            GLOBAL_REGION_SCHEDULER = callMethod(Bukkit.class, "getGlobalRegionScheduler");
        }
        return GLOBAL_REGION_SCHEDULER;
    }
    
    public static <T> T callMethod(Class<?> clazz, Object object, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            return (T) clazz.getDeclaredMethod(methodName, parameterTypes).invoke(object, args);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static <T> T callMethod(Object object, String methodName, Class<?>[] parameterTypes, Object... args) {
        return callMethod(object.getClass(), object, methodName, parameterTypes, args);
    }

    public static <T> T callMethod(Class<?> clazz, String methodName) {
        return callMethod(clazz, null, methodName, new Class[]{});
    }
    
    public static void runTask(Plugin plugin, Runnable runnable) {
    	Object globalRegionScheduler = getGlobalRegionScheduler();
        callMethod(globalRegionScheduler, "run", new Class[]{Plugin.class, Consumer.class}, plugin, (Consumer<?>) (task) -> runnable.run());
    }
}
