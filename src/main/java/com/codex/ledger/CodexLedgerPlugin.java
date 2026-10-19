package com.codex.ledger;

import com.codex.ledger.core.Day;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Offer;
import com.codex.ledger.core.Report;
import com.codex.ledger.core.SafeDeath;
import com.codex.ledger.core.Store;
import com.codex.ledger.overlay.WorthPlaque;
import com.codex.ledger.ui.Format;
import com.codex.ledger.ui.ItemIcons;
import com.codex.ledger.ui.LedgerPanel;
import com.codex.ledger.ui.Sigil;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Codex Ledger",
	description = "Your net worth over time: bank, inventory, gear, Grand Exchange and more, with a day book of where your gold came from and went",
	tags = {"wealth", "net worth", "bank", "value", "gp", "profit", "grand exchange", "ledger", "codex"}
)
public class CodexLedgerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(CodexLedgerPlugin.class);
	private static final String LEDGER_KEY = "ledger";
	/** Ticks between revaluing what is held, about a minute. */
	private static final int REVALUE_TICKS = 100;
	/**
	 * Ticks between saves while something has changed, about five minutes. The ledger is also saved
	 * on logout, on a profile switch, when the plugin stops and when the client closes.
	 */
	private static final int SAVE_TICKS = 500;
	/** Ticks between refreshing the panel and plaque while the ledger keeps changing. */
	private static final int PUBLISH_TICKS = 3;
	/**
	 * Ticks after login before anything is counted, so the exchange offers and the rune pouch have
	 * arrived and are counted in the same breath as the inventory they may have gone into.
	 */
	private static final int SYNC_TICKS = 3;
	/** Ticks after login before the summary. */
	private static final int SUMMARY_TICKS = 8;
	private static final int GE_SLOTS = 8;
	private static final int[] POUCH_RUNES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};
	private static final int[] POUCH_AMOUNTS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Gson gson;

	@Inject
	private CodexLedgerConfig config;

	private final WorthPlaque plaque = new WorthPlaque();
	private final Map<Integer, String> names = new HashMap<>();

	private LedgerPanel panel;
	private NavigationButton navButton;

	private String profile;
	private Ledger ledger;
	private boolean dirty;
	private boolean unsaved;
	private int ticks;
	private int loggedInTicks = -1;
	private long worthAtLogin = -1;
	/** True once the offers and rune pouch have been read since login; nothing is settled before. */
	private boolean synced;
	private boolean pouchChanged;
	/** Set at the login screen, so the summary is written once a login and not again after a world hop. */
	private boolean fromLoginScreen;
	private boolean summaryDue;

	@Provides
	CodexLedgerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CodexLedgerConfig.class);
	}

	@Override
	protected void startUp()
	{
		ItemIcons icons = (itemId, onLoaded) ->
		{
			AsyncBufferedImage image = itemManager.getImage(itemId);
			image.onLoaded(onLoaded);
			return image;
		};
		panel = new LedgerPanel(icons);
		navButton = NavigationButton.builder()
			.tooltip("Codex Ledger")
			.icon(Sigil.icon(16))
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		plaque.setEnabled(config.showPlaque());
		overlayManager.add(plaque);
		clientThread.invokeLater(() ->
		{
			loadProfile();
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				loggedInTicks = SYNC_TICKS;
				observeAll();
			}
			publish();
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(plaque);
		clientToolbar.removeNavigation(navButton);
		clientThread.invokeLater(() ->
		{
			save();
			ledger = null;
			profile = null;
			names.clear();
			synced = false;
			loggedInTicks = -1;
		});
		panel = null;
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// ConfigManager writes its changes to disk in this same event, before a save queued on the
		// client thread runs, so a save made here has to be sent on by hand or it is never written.
		CompletableFuture<Void> done = new CompletableFuture<>();
		clientThread.invokeLater(() ->
		{
			try
			{
				if (save())
				{
					configManager.sendConfig();
				}
			}
			finally
			{
				done.complete(null);
			}
		});
		event.waitFor(done);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			if (loggedInTicks < 0)
			{
				loggedInTicks = 0;
			}
			if (fromLoginScreen)
			{
				fromLoginScreen = false;
				summaryDue = true;
				worthAtLogin = ledger != null && ledger.isReady() ? ledger.getTotal() : -1;
			}
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			// The client forgets the offers while away; they are read afresh after logging back in.
			loggedInTicks = -1;
			synced = false;
			if (state == GameState.LOGIN_SCREEN)
			{
				fromLoginScreen = true;
				summaryDue = false;
				save();
				dirty = true;
				publish();
			}
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		save();
		loadProfile();
		worthAtLogin = ledger != null && ledger.isReady() ? ledger.getTotal() : -1;
		synced = false;
		if (client.getGameState() == GameState.LOGGED_IN && loggedInTicks >= SYNC_TICKS)
		{
			observeAll();
		}
		publish();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// The ledger itself is saved in this group too, every few minutes; only the settings matter here.
		if (CodexLedgerConfig.GROUP.equals(event.getGroup()) && event.getProfile() == null)
		{
			plaque.setEnabled(config.showPlaque());
			if ("goal".equals(event.getKey()) || "goalName".equals(event.getKey()))
			{
				clientThread.invokeLater(this::publish);
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || ledger == null)
		{
			return;
		}
		Store store = store(event.getContainerId());
		if (store != null)
		{
			ledger.observe(store, contents(event.getItemContainer()));
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		int slot = event.getSlot();
		if (ledger == null || !synced || slot < 0 || slot >= GE_SLOTS || client.getGameState() != GameState.LOGGED_IN)
		{
			// Until the offers are read whole after login, a single slot could look like a collection.
			return;
		}
		ledger.offer(slot, offer(event.getOffer()));
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		// What the death takes leaves the account a few ticks later, when the player is sent home.
		// A death in a raid or a safe minigame takes nothing; what is eaten after it is supplies.
		Player me = client.getLocalPlayer();
		if (ledger != null && me != null && event.getActor() == me && !safeDeath(me))
		{
			ledger.died(System.currentTimeMillis());
		}
	}

	/** True where the game keeps everything on death: the raids and the safe minigames and arenas. */
	private boolean safeDeath(Player me)
	{
		LocalPoint local = me.getLocalLocation();
		int region = local == null ? -1 : WorldPoint.fromLocalInstance(client, local).getRegionID();
		return SafeDeath.isSafe(region, client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) > 0,
			client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		for (int varbit : POUCH_RUNES)
		{
			pouchChanged |= event.getVarbitId() == varbit;
		}
		for (int varbit : POUCH_AMOUNTS)
		{
			pouchChanged |= event.getVarbitId() == varbit;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (ledger == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		ticks++;
		if (loggedInTicks >= 0)
		{
			loggedInTicks++;
		}
		if (!synced)
		{
			if (loggedInTicks < SYNC_TICKS)
			{
				return;
			}
			observeAll();
		}
		if (pouchChanged)
		{
			pouchChanged = false;
			ledger.observe(Store.RUNE_POUCH, runePouch());
		}
		if (!pricesReady())
		{
			return;
		}
		ledger.setAtExchange(atExchange());
		if (ledger.hasPending() || ticks % REVALUE_TICKS == 0)
		{
			if (ledger.settle(System.currentTimeMillis(), itemManager::getItemPrice, ZoneId.systemDefault()))
			{
				dirty = true;
				unsaved = true;
			}
		}
		if (summaryDue && loggedInTicks >= SUMMARY_TICKS)
		{
			summaryDue = false;
			summary();
		}
		if (unsaved && ticks % SAVE_TICKS == 0)
		{
			save();
		}
		if (dirty && ticks % PUBLISH_TICKS == 0 || ticks % REVALUE_TICKS == 1)
		{
			publish();
		}
	}

	/** Prices load in the background after the client starts; nothing is valued before they are in. */
	private boolean pricesReady()
	{
		return itemManager.getItemPrice(ItemID.ABYSSAL_WHIP) > 0;
	}

	private static Store store(int containerId)
	{
		switch (containerId)
		{
			case InventoryID.BANK:
				return Store.BANK;
			case InventoryID.INV:
				return Store.INVENTORY;
			case InventoryID.WORN:
				return Store.EQUIPMENT;
			case InventoryID.SEED_VAULT:
				return Store.SEED_VAULT;
			case InventoryID.LOOTING_BAG:
				return Store.LOOTING_BAG;
			default:
				return null;
		}
	}

	private Map<Integer, Long> contents(ItemContainer container)
	{
		Map<Integer, Long> items = new HashMap<>();
		if (container == null)
		{
			return items;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			items.merge(itemManager.canonicalize(item.getId()), (long) item.getQuantity(), Long::sum);
		}
		return items;
	}

	/** An offer as the ledger keeps it, or null for an empty slot. */
	private static Offer offer(GrandExchangeOffer offer)
	{
		if (offer == null)
		{
			return null;
		}
		switch (offer.getState())
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return new Offer(offer.getItemId(), true, offer.getTotalQuantity(), offer.getPrice(), offer.getQuantitySold(),
					offer.getSpent(), offer.getState() == GrandExchangeOfferState.BUYING);
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return new Offer(offer.getItemId(), false, offer.getTotalQuantity(), offer.getPrice(), offer.getQuantitySold(),
					offer.getSpent(), offer.getState() == GrandExchangeOfferState.SELLING);
			default:
				return null;
		}
	}

	/** The runes in the rune pouch, read from the varbits the game keeps them in whether the pouch is carried or not. */
	private Map<Integer, Long> runePouch()
	{
		Map<Integer, Long> runes = new HashMap<>();
		EnumComposition ids = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		for (int i = 0; i < POUCH_RUNES.length; i++)
		{
			int rune = client.getVarbitValue(POUCH_RUNES[i]);
			int amount = client.getVarbitValue(POUCH_AMOUNTS[i]);
			int itemId = rune > 0 && ids != null ? ids.getIntValue(rune) : -1;
			if (itemId > 0 && amount > 0)
			{
				runes.merge(itemId, (long) amount, Long::sum);
			}
		}
		return runes;
	}

	/** True while the Grand Exchange or a bank's collection box is open, where offers are collected. */
	private boolean atExchange()
	{
		return visible(InterfaceID.GeOffers.UNIVERSE) || visible(InterfaceID.GeCollect.UNIVERSE);
	}

	private boolean visible(int component)
	{
		Widget widget = client.getWidget(component);
		return widget != null && !widget.isHidden();
	}

	/**
	 * Reads every place the client already has, all at once: after login once the offers have
	 * arrived, and when the plugin starts with the player logged in.
	 */
	private void observeAll()
	{
		synced = true;
		pouchChanged = false;
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN, InventoryID.BANK, InventoryID.SEED_VAULT, InventoryID.LOOTING_BAG})
		{
			ItemContainer container = client.getItemContainer(id);
			if (container != null)
			{
				ledger.observe(store(id), contents(container));
			}
		}
		ledger.observe(Store.RUNE_POUCH, runePouch());
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; i < GE_SLOTS; i++)
		{
			ledger.offer(i, offers != null && i < offers.length ? offer(offers[i]) : null);
		}
	}

	private void loadProfile()
	{
		profile = configManager.getRSProfileKey();
		ledger = null;
		if (profile == null)
		{
			return;
		}
		try
		{
			String saved = configManager.getRSProfileConfiguration(CodexLedgerConfig.GROUP, LEDGER_KEY);
			if (saved != null && !saved.isEmpty())
			{
				Ledger read = gson.fromJson(saved, Ledger.class);
				ledger = read == null ? null : read.repair();
			}
		}
		catch (JsonParseException e)
		{
			log.warn("Could not read the saved ledger, starting it afresh", e);
		}
		if (ledger == null)
		{
			ledger = new Ledger();
		}
		unsaved = false;
		dirty = true;
	}

	/** Writes the ledger to the profile if it changed, and says whether it did. */
	private boolean save()
	{
		if (profile == null || ledger == null || !unsaved)
		{
			return false;
		}
		unsaved = false;
		configManager.setConfiguration(CodexLedgerConfig.GROUP, profile, LEDGER_KEY, gson.toJson(ledger));
		return true;
	}

	private void summary()
	{
		if (!config.loginSummary() || ledger == null || !ledger.isReady())
		{
			return;
		}
		long total = ledger.getTotal();
		ZoneId zone = ZoneId.systemDefault();
		LocalDate today = Instant.now().atZone(zone).toLocalDate();
		long change = Report.of(ledger, 1, today, 0).change();
		ChatMessageBuilder line = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT).append("Codex Ledger: ")
			.append(ChatColorType.NORMAL).append("your worth is ")
			.append(ChatColorType.HIGHLIGHT).append(Format.coins(total))
			.append(ChatColorType.NORMAL).append(", " + Format.signed(change) + " today.");
		if (worthAtLogin >= 0 && worthAtLogin != total)
		{
			line.append(" While you were away it moved " + Format.signed(total - worthAtLogin) + ".");
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(line.build())
			.build());
	}

	/** Sends the panel a copy of the ledger with every name it needs, so Swing never reads what the client thread writes. */
	private void publish()
	{
		dirty = false;
		LedgerPanel target = panel;
		long now = System.currentTimeMillis();
		if (ledger == null)
		{
			plaque.show(null, 0);
			if (target != null)
			{
				SwingUtilities.invokeLater(() -> target.update(null, Collections.emptyMap(), null, LedgerPanel.State.LOGGED_OUT, now));
			}
			return;
		}
		Ledger copy = gson.fromJson(gson.toJson(ledger), Ledger.class).repair();
		for (int id : copy.holdings().keySet())
		{
			name(id);
		}
		for (Offer offer : copy.getOffers().values())
		{
			name(offer.getItem());
		}
		for (int id : copy.getBook().keySet())
		{
			name(id);
		}
		for (Day day : copy.getDays())
		{
			for (int id : day.getItems().keySet())
			{
				name(id);
			}
			for (int id : day.getDeals().keySet())
			{
				name(id);
			}
		}
		Map<Integer, String> shownNames = new HashMap<>(names);
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		Player player = client.getLocalPlayer();
		String name = loggedIn && player != null ? player.getName() : null;
		LedgerPanel.State state = !loggedIn ? LedgerPanel.State.LOGGED_OUT : copy.isReady() ? LedgerPanel.State.READY : LedgerPanel.State.WAITING_FOR_BANK;
		if (copy.isReady() && loggedIn)
		{
			plaque.show(copy.getTotal(), Report.of(copy, 1, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate(), 0).change());
		}
		else
		{
			plaque.show(null, 0);
		}
		if (target != null)
		{
			LedgerPanel.Goal goal = new LedgerPanel.Goal(Math.max(0, config.goal()) * 1_000_000L, config.goalName());
			SwingUtilities.invokeLater(() ->
			{
				target.setGoal(goal);
				target.update(copy, shownNames, name, state, now);
			});
		}
	}

	private void name(int itemId)
	{
		names.computeIfAbsent(itemId, id -> itemManager.getItemComposition(id).getName());
	}
}
