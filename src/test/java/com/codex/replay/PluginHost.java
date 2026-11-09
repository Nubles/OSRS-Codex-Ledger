package com.codex.replay;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import org.mockito.Answers;
import org.mockito.Mockito;

/**
 * Runs a whole plugin on a replay, as RuneLite would: built by Guice with its config, started,
 * and sent the game's events and a {@link GameTick} each tick through its {@code @Subscribe}
 * methods. The game is the {@link FakeGame}; the config and saved state are kept in memory;
 * the client thread and background executor run work at once; everything else RuneLite would
 * inject (the overlay manager, the toolbar...) is a Mockito mock that does nothing, except item
 * names and prices, which come from the script's {@code item} lines.
 *
 * <p>What a script checks is read out of the plugin by a path of fields and getters, declared in
 * the script: {@code watch step plaque.shown.step} reads the plugin's {@code plaque} field, then
 * its {@code shown}, then that one's {@code step}, after every tick, as the reading {@code step}.
 * A number picks from a list or an array: {@code tripPlaque.shown.lines.0.text}.
 *
 * <p>Commands it adds: {@code login} (the events of logging in: game states and the account's
 * profile), {@code config KEY VALUE} (a setting of the plugin's config, by its method name), {@code saved KEY VALUE} (a key of the plugin's per-account saved state), {@code saved-in GROUP
 * KEY VALUE} and {@code config-in GROUP KEY VALUE} (another plugin's saved state and settings), {@code watch
 * NAME PATH}, {@code call PATH} (calls a no-argument method, such as a panel action), and {@code shutdown}
 * and {@code startup} (the plugin turned off and on again).
 *
 * <p>Shared by every Codex plugin: keep this file identical across repositories.
 */
public final class PluginHost implements Rig
{
	private final FakeGame game;
	private final Plugin plugin;
	private final Map<String, Object> settings = new HashMap<>();
	/** Saved state: "group.key" for the whole client, "rs.group.key" for this account. */
	private final Map<String, String> store = new TreeMap<>();
	private final Map<String, String> watches = new LinkedHashMap<>();
	private final Map<String, Method> subscribers = new HashMap<>();
	private final Gson gson = new Gson();
	private String group;
	private Object config;

	static
	{
		System.setProperty("java.awt.headless", "true");
	}

	/** For {@link Replay#play}: {@code Replay.play(script, PluginHost.of(MyPlugin.class))}. */
	public static Function<FakeGame, Rig> of(Class<? extends Plugin> type)
	{
		return game -> new PluginHost(game, type);
	}

	private PluginHost(FakeGame game, Class<? extends Plugin> type)
	{
		this.game = game;
		try
		{
			Constructor<? extends Plugin> ctor = type.getDeclaredConstructor();
			ctor.setAccessible(true);
			plugin = ctor.newInstance();
			Injector parent = Guice.createInjector(new Bindings(type));
			Module own = binder ->
			{
				@SuppressWarnings("unchecked")
				Class<Plugin> t = (Class<Plugin>) type;
				binder.bind(t).toInstance(plugin);
				binder.install(plugin);
			};
			parent.createChildInjector(own).injectMembers(plugin);
			for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
			{
				for (Method m : c.getDeclaredMethods())
				{
					if (m.isAnnotationPresent(Subscribe.class) && m.getParameterCount() == 1)
					{
						m.setAccessible(true);
						subscribers.putIfAbsent(m.getParameterTypes()[0].getName(), m);
					}
				}
			}
			Method startUp = Plugin.class.getDeclaredMethod("startUp");
			startUp.setAccessible(true);
			startUp.invoke(plugin);
		}
		catch (InvocationTargetException e)
		{
			throw new IllegalStateException("The plugin failed to start: " + e.getCause(), e.getCause());
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	public Plugin plugin()
	{
		return plugin;
	}

	// ---- the script's commands -------------------------------------------------------------

	@Override
	public boolean command(String verb, List<String> args)
	{
		switch (verb)
		{
			case "config":
				setting(args.get(0), args.get(1));
				return true;
			case "saved":
				store.put("rs." + group + "." + args.get(0), args.get(1));
				return true;
			case "saved-in":
				// Another plugin's per-account state, such as RuneLite's own time tracking.
				store.put("rs." + args.get(0) + "." + args.get(1), args.get(2));
				return true;
			case "config-in":
				// Another plugin's whole-client setting, such as whether a plugin is turned on.
				store.put(args.get(0) + "." + args.get(1), args.get(2));
				return true;
			case "watch":
				read(args.get(1));
				watches.put(args.get(0), args.get(1));
				return true;
			case "call":
				call(args.get(0));
				return true;
			case "login":
				login();
				return true;
			case "shutdown":
				lifecycle("shutDown");
				return true;
			case "startup":
				lifecycle("startUp");
				return true;
			default:
				return false;
		}
	}

	/** Turns the plugin off or on again, as the plugin list's switch does. */
	private void lifecycle(String name)
	{
		try
		{
			Method m = Plugin.class.getDeclaredMethod(name);
			m.setAccessible(true);
			m.invoke(plugin);
		}
		catch (InvocationTargetException e)
		{
			Throwable t = e.getCause();
			throw t instanceof RuntimeException ? (RuntimeException) t : new IllegalStateException(t);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Logs in as the game does: logging in, the account's profile, the scene loading, then in,
	 * then what you carry and wear. Put the player on a tile first.
	 */
	private void login()
	{
		game.state(GameState.LOGGING_IN);
		post(new RuneScapeProfileChanged(null, "replay"));
		game.state(GameState.LOADING);
		game.state(GameState.LOGGED_IN);
		// The game sends what you carry and wear once you are in, even when it is nothing.
		for (int id : new int[]{net.runelite.api.gameval.InventoryID.INV, net.runelite.api.gameval.InventoryID.WORN})
		{
			if (game.client().getItemContainer(id) == null)
			{
				game.emptyContainer(id);
			}
			else
			{
				container(id);
			}
		}
	}

	private void setting(String key, String value)
	{
		Method m = configMethod(key);
		settings.put(key, convert(value, m.getReturnType()));
		Object event = newEvent("net.runelite.client.events.ConfigChanged");
		set(event, "setGroup", String.class, group);
		set(event, "setKey", String.class, key);
		set(event, "setNewValue", String.class, value);
		post(event);
	}

	private Method configMethod(String key)
	{
		for (Method m : configType().getMethods())
		{
			if (m.getName().equals(key) && m.getParameterCount() == 0)
			{
				return m;
			}
		}
		throw new IllegalArgumentException("No setting " + key + " in " + configType().getSimpleName());
	}

	@Override
	public Map<String, String> tick(long now)
	{
		post(new GameTick());
		Map<String, String> r = new TreeMap<>();
		watches.forEach((name, path) -> r.put(name, String.valueOf(read(path))));
		store.forEach((k, v) ->
		{
			if (k.startsWith("rs." + group + "."))
			{
				r.put("saved." + k.substring(group.length() + 4), v);
			}
		});
		return r;
	}

	// ---- the game's events -----------------------------------------------------------------

	/** Sends an event to the plugin's {@code @Subscribe} method for it, when it has one. */
	public void post(Object event)
	{
		Method m = subscribers.get(event.getClass().getName());
		if (m == null)
		{
			return;
		}
		try
		{
			m.invoke(plugin, event);
		}
		catch (InvocationTargetException e)
		{
			Throwable t = e.getCause();
			throw t instanceof RuntimeException ? (RuntimeException) t : new IllegalStateException(t);
		}
		catch (IllegalAccessException e)
		{
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void spawned(TileObject o)
	{
		if (o instanceof GameObject)
		{
			GameObjectSpawned e = new GameObjectSpawned();
			e.setGameObject((GameObject) o);
			e.setTile(game.tile(o));
			post(e);
		}
		else if (o instanceof WallObject)
		{
			WallObjectSpawned e = new WallObjectSpawned();
			e.setWallObject((WallObject) o);
			e.setTile(game.tile(o));
			post(e);
		}
	}

	@Override
	public void despawned(TileObject o)
	{
		if (o instanceof GameObject)
		{
			GameObjectDespawned e = new GameObjectDespawned();
			e.setGameObject((GameObject) o);
			e.setTile(game.tile(o));
			post(e);
		}
		else if (o instanceof WallObject)
		{
			WallObjectDespawned e = new WallObjectDespawned();
			e.setWallObject((WallObject) o);
			e.setTile(game.tile(o));
			post(e);
		}
	}

	@Override
	public void npcSpawned(NPC npc)
	{
		post(new NpcSpawned(npc));
	}

	@Override
	public void npcDespawned(NPC npc)
	{
		post(new NpcDespawned(npc));
	}

	@Override
	public void npcChanged(NPC npc, NPCComposition old)
	{
		post(new NpcChanged(npc, old));
	}

	@Override
	public void varbit(int id, int value)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(id);
		e.setValue(value);
		post(e);
	}

	@Override
	public void varp(int id, int value)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarpId(id);
		e.setValue(value);
		post(e);
	}

	@Override
	public void chat(String type, String message)
	{
		post(new ChatMessage(null, ChatMessageType.valueOf(type), "", message, null, 0));
	}

	@Override
	public void gameState(GameState state)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		post(e);
	}

	@Override
	public void container(int containerId)
	{
		post(new ItemContainerChanged(containerId, game.client().getItemContainer(containerId)));
	}

	@Override
	public void stat(Skill skill)
	{
		Client c = game.client();
		post(new StatChanged(skill, c.getSkillExperience(skill), c.getRealSkillLevel(skill), c.getBoostedSkillLevel(skill)));
	}

	// ---- reading the plugin ----------------------------------------------------------------

	/** Follows a path of fields and no-argument methods from the plugin; null part way is null. */
	private Object read(String path)
	{
		Object at = plugin;
		for (String part : path.split("\\."))
		{
			if (at == null)
			{
				return null;
			}
			at = step(at, part, path);
		}
		return at;
	}

	private void call(String path)
	{
		int dot = path.lastIndexOf('.');
		Object on = dot < 0 ? plugin : read(path.substring(0, dot));
		step(on, path.substring(dot + 1), path);
	}

	private static Object step(Object on, String part, String path)
	{
		if (part.matches("\\d+") && (on instanceof List || on.getClass().isArray()))
		{
			// An index into a list or an array: lines.0 is the first line.
			int i = Integer.parseInt(part);
			int n = on instanceof List ? ((List<?>) on).size() : java.lang.reflect.Array.getLength(on);
			return i >= n ? null : on instanceof List ? ((List<?>) on).get(i) : java.lang.reflect.Array.get(on, i);
		}
		String getter = "get" + Character.toUpperCase(part.charAt(0)) + part.substring(1);
		String is = "is" + Character.toUpperCase(part.charAt(0)) + part.substring(1);
		try
		{
			for (Class<?> c = on.getClass(); c != null; c = c.getSuperclass())
			{
				for (Method m : c.getDeclaredMethods())
				{
					if (m.getParameterCount() == 0 && (m.getName().equals(part) || m.getName().equals(getter) || m.getName().equals(is)))
					{
						m.setAccessible(true);
						return m.invoke(on);
					}
				}
				for (Field f : c.getDeclaredFields())
				{
					if (f.getName().equals(part))
					{
						f.setAccessible(true);
						return f.get(on);
					}
				}
			}
		}
		catch (InvocationTargetException e)
		{
			throw new IllegalStateException("Reading " + path + " threw " + e.getCause(), e.getCause());
		}
		catch (IllegalAccessException e)
		{
			throw new IllegalStateException(e);
		}
		throw new IllegalArgumentException("Nothing called " + part + " on " + on.getClass().getSimpleName() + " (reading " + path + ")");
	}

	// ---- what RuneLite would inject --------------------------------------------------------

	private final class Bindings extends AbstractModule
	{
		private final Class<?> type;

		Bindings(Class<?> type)
		{
			this.type = type;
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		protected void configure()
		{
			bind(Client.class).toInstance(game.client());
			bind(ClientThread.class).toInstance(clientThread());
			bind(ConfigManager.class).toInstance(configManager());
			bind(Gson.class).toInstance(gson);
			bind(ScheduledExecutorService.class).toInstance(executor());
			for (Class<?> needed : injected(type))
			{
				bind((Class) needed).toInstance(needed == ItemManager.class ? itemManager() : Mockito.mock(needed));
			}
		}
	}

	/** RuneLite's own types the plugin's classes have injected, to stand in for with mocks. */
	private static Set<Class<?>> injected(Class<?> pluginType)
	{
		Set<Class<?>> out = new LinkedHashSet<>();
		for (Class<?> c : ownClasses(pluginType))
		{
			for (Field f : c.getDeclaredFields())
			{
				if (f.isAnnotationPresent(Inject.class) || f.isAnnotationPresent(javax.inject.Inject.class))
				{
					consider(f.getType(), out);
				}
			}
			for (Constructor<?> k : c.getDeclaredConstructors())
			{
				if (k.isAnnotationPresent(Inject.class) || k.isAnnotationPresent(javax.inject.Inject.class))
				{
					for (Class<?> p : k.getParameterTypes())
					{
						consider(p, out);
					}
				}
			}
		}
		return out;
	}

	private static void consider(Class<?> t, Set<Class<?>> out)
	{
		String n = t.getName();
		boolean known = t == Client.class || t == ClientThread.class || t == ConfigManager.class || t == Gson.class
			|| t == ScheduledExecutorService.class || Config.class.isAssignableFrom(t);
		if (!known && !t.isPrimitive() && (n.startsWith("net.runelite.") || n.startsWith("okhttp3.") || n.startsWith("java.")))
		{
			out.add(t);
		}
	}

	/** The classes compiled from the plugin's own package and below. */
	private static List<Class<?>> ownClasses(Class<?> pluginType)
	{
		String pkg = pluginType.getPackage().getName();
		try
		{
			Path root = Paths.get(pluginType.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path dir = root.resolve(pkg.replace('.', File.separatorChar));
			try (Stream<Path> files = Files.walk(dir))
			{
				List<String> names = files.map(p -> root.relativize(p).toString())
					.filter(n -> n.endsWith(".class"))
					.map(n -> n.substring(0, n.length() - 6).replace(File.separatorChar, '.'))
					.collect(Collectors.toList());
				List<Class<?>> out = new ArrayList<>();
				for (String n : names)
				{
					out.add(Class.forName(n, false, pluginType.getClassLoader()));
				}
				return out;
			}
		}
		catch (URISyntaxException | java.io.IOException | ClassNotFoundException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static ClientThread clientThread()
	{
		return Mockito.mock(ClientThread.class, inv ->
		{
			switch (inv.getMethod().getName())
			{
				case "invoke":
				case "invokeLater":
				case "invokeAtTickEnd":
					Object r = inv.getArgument(0);
					if (r instanceof Runnable)
					{
						((Runnable) r).run();
					}
					else if (r instanceof BooleanSupplier)
					{
						((BooleanSupplier) r).getAsBoolean();
					}
					return null;
				case "isClientThread":
					return true;
				default:
					return Answers.RETURNS_DEFAULTS.answer(inv);
			}
		});
	}

	/** Item names and prices from the script ({@code item}); everything else as a mock. */
	private ItemManager itemManager()
	{
		return Mockito.mock(ItemManager.class, inv ->
		{
			switch (inv.getMethod().getName())
			{
				case "getItemComposition":
					return game.client().getItemDefinition((Integer) inv.getArgument(0));
				case "getItemPrice":
					return (long) game.price((Integer) inv.getArgument(0));
				case "canonicalize":
					return inv.getArgument(0);
				case "getImage":
					// A blank picture that never loads, as before the cache has it.
					return new net.runelite.client.util.AsyncBufferedImage(Mockito.mock(ClientThread.class), 36, 32,
						java.awt.image.BufferedImage.TYPE_INT_ARGB);
				default:
					return Answers.RETURNS_DEFAULTS.answer(inv);
			}
		});
	}

	/** Runs submitted work at once; repeating work (autosaves) never runs. */
	private static ScheduledExecutorService executor()
	{
		return Mockito.mock(ScheduledExecutorService.class, inv ->
		{
			switch (inv.getMethod().getName())
			{
				case "execute":
					((Runnable) inv.getArgument(0)).run();
					return null;
				case "submit":
					Object task = inv.getArgument(0);
					if (task instanceof Runnable)
					{
						((Runnable) task).run();
						return CompletableFuture.completedFuture(null);
					}
					return CompletableFuture.completedFuture(((java.util.concurrent.Callable<?>) task).call());
				case "schedule":
				case "scheduleAtFixedRate":
				case "scheduleWithFixedDelay":
					return Mockito.mock(ScheduledFuture.class);
				default:
					return Answers.RETURNS_DEFAULTS.answer(inv);
			}
		});
	}

	private ConfigManager configManager()
	{
		return Mockito.mock(ConfigManager.class, inv ->
		{
			String name = inv.getMethod().getName();
			Object[] a = inv.getArguments();
			switch (name)
			{
				case "getConfig":
					return config((Class<?>) a[0]);
				case "getRSProfileKey":
					return "replay";
				case "getRSProfileConfiguration":
					return typed(store.get("rs." + a[0] + "." + a[1]), a.length > 2 ? (Type) a[2] : String.class);
				case "setRSProfileConfiguration":
					store.put("rs." + a[0] + "." + a[1], text(a[2]));
					return null;
				case "unsetRSProfileConfiguration":
					store.remove("rs." + a[0] + "." + a[1]);
					return null;
				case "getConfiguration":
					// (group, key), (group, profile, key), (group, key, type) or (group, profile, key, type)
					return a.length == 2 ? store.get(a[0] + "." + a[1])
						: a.length == 3 && a[2] instanceof String ? store.get(a[0] + "." + a[2])
						: a.length == 3 ? typed(store.get(a[0] + "." + a[1]), (Type) a[2])
						: typed(store.get(a[0] + "." + a[2]), (Type) a[3]);
				case "setConfiguration":
					store.put(a[0] + "." + a[a.length - 2], text(a[a.length - 1]));
					return null;
				case "unsetConfiguration":
					store.remove(a[0] + "." + a[a.length - 1]);
					return null;
				case "getRSProfileConfigurationKeys":
				{
					String prefix = "rs." + a[0] + "." + (a[2] == null ? "" : a[2]);
					return store.keySet().stream().filter(k -> k.startsWith(prefix))
						.map(k -> k.substring(("rs." + a[0] + ".").length())).collect(Collectors.toList());
				}
				case "getConfigurationKeys":
					return store.keySet().stream().filter(k -> k.startsWith((String) a[0])).collect(Collectors.toList());
				default:
					return Answers.RETURNS_DEFAULTS.answer(inv);
			}
		});
	}

	private Class<?> configType()
	{
		if (config == null)
		{
			throw new IllegalStateException("The plugin has no config");
		}
		return config.getClass().getInterfaces()[0];
	}

	/** The plugin's config: the script's settings, else each item's default. */
	private Object config(Class<?> type)
	{
		if (config != null)
		{
			return config;
		}
		ConfigGroup g = type.getAnnotation(ConfigGroup.class);
		group = g == null ? "" : g.value();
		config = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, a) ->
		{
			if (m.getDeclaringClass() == Object.class)
			{
				return m.getName().equals("equals") ? self == a[0] : m.getName().equals("hashCode") ? 0 : type.getSimpleName();
			}
			if (settings.containsKey(m.getName()) && m.getParameterCount() == 0)
			{
				return settings.get(m.getName());
			}
			if (m.isDefault())
			{
				return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflectSpecial(m, type)
					.bindTo(self).invokeWithArguments(a == null ? new Object[0] : a);
			}
			return null;
		});
		return config;
	}

	private String text(Object value)
	{
		if (value == null)
		{
			return null;
		}
		if (value instanceof String || value instanceof Number || value instanceof Boolean)
		{
			return value.toString();
		}
		if (value instanceof Enum)
		{
			return ((Enum<?>) value).name();
		}
		return gson.toJson(value);
	}

	private Object typed(String value, Type type)
	{
		if (value == null || type == String.class)
		{
			return value;
		}
		if (type instanceof Class && (((Class<?>) type).isPrimitive() || Number.class.isAssignableFrom((Class<?>) type)
			|| type == Boolean.class || ((Class<?>) type).isEnum()))
		{
			return convert(value, (Class<?>) type);
		}
		return gson.fromJson(value, type);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object convert(String value, Class<?> type)
	{
		if (type == String.class)
		{
			return value;
		}
		if (type == boolean.class || type == Boolean.class)
		{
			return value.equals("on") || value.equals("true");
		}
		if (type == int.class || type == Integer.class)
		{
			return Integer.parseInt(value);
		}
		if (type == long.class || type == Long.class)
		{
			return Long.parseLong(value);
		}
		if (type == double.class || type == Double.class)
		{
			return Double.parseDouble(value);
		}
		if (type.isEnum())
		{
			return Enum.valueOf((Class) type, value);
		}
		if (type == java.awt.Color.class)
		{
			return java.awt.Color.decode(value);
		}
		throw new IllegalArgumentException("Cannot set a " + type.getSimpleName() + " from a script");
	}

	private static Object newEvent(String type)
	{
		try
		{
			return Class.forName(type).getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static void set(Object on, String setter, Class<?> type, Object value)
	{
		try
		{
			on.getClass().getMethod(setter, type).invoke(on, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
