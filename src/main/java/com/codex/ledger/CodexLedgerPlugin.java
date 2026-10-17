package com.codex.ledger;

import com.codex.ledger.core.Day;
import com.codex.ledger.core.Ledger;
import com.codex.ledger.core.Report;
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
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
	/** Ticks between saves while something has changed. */
	private static final int SAVE_TICKS = 50;
	/** Ticks after login before the summary, so the exchange offers have arrived. */
	private static final int SUMMARY_TICKS = 8;
	private static final int GE_SLOTS = 8;

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
	@SuppressWarnings("unchecked")
	private final Map<Integer, Long>[] exchange = new Map[GE_SLOTS];

	private LedgerPanel panel;
	private NavigationButton navButton;

	private String profile;
	private Ledger ledger;
	private boolean dirty;
	private boolean unsaved;
	private int ticks;
	private int loggedInTicks = -1;
	private long worthAtLogin = -1;

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
				loggedInTicks = 0;
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
			clearExchange();
		});
		panel = null;
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
				worthAtLogin = ledger != null && ledger.isReady() ? ledger.getTotal() : -1;
			}
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			loggedInTicks = -1;
			clearExchange();
			save();
			dirty = true;
			publish();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		save();
		loadProfile();
		clearExchange();
		worthAtLogin = ledger != null && ledger.isReady() ? ledger.getTotal() : -1;
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			observeAll();
		}
		publish();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CodexLedgerConfig.GROUP.equals(event.getGroup()))
		{
			plaque.setEnabled(config.showPlaque());
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
		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();
		if (ledger == null || slot < 0 || slot >= GE_SLOTS
			|| (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN))
		{
			// The client clears every offer while logging in and hopping; those are not real.
			return;
		}
		exchange[slot] = offerContents(offer);
		Map<Integer, Long> all = new HashMap<>();
		for (Map<Integer, Long> s : exchange)
		{
			if (s != null)
			{
				s.forEach((id, q) -> all.merge(id, q, Long::sum));
			}
		}
		ledger.observe(Store.GRAND_EXCHANGE, all);
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
		if (!pricesReady())
		{
			return;
		}
		if (ledger.hasPending() || ticks % REVALUE_TICKS == 0)
		{
			if (ledger.settle(System.currentTimeMillis(), itemManager::getItemPrice, ZoneId.systemDefault()))
			{
				dirty = true;
				unsaved = true;
			}
		}
		if (loggedInTicks == SUMMARY_TICKS)
		{
			summary();
		}
		if (unsaved && ticks % SAVE_TICKS == 0)
		{
			save();
		}
		if (dirty && ticks % 2 == 0 || ticks % REVALUE_TICKS == 1)
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

	/** What an offer holds right now: the items it has bought or has left to sell, and the coins in it. */
	private static Map<Integer, Long> offerContents(GrandExchangeOffer offer)
	{
		Map<Integer, Long> items = new HashMap<>();
		long coins;
		long held;
		switch (offer.getState())
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				held = offer.getQuantitySold();
				coins = offer.getPrice() * offer.getTotalQuantity() - offer.getSpent();
				break;
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				held = offer.getTotalQuantity() - offer.getQuantitySold();
				coins = offer.getSpent();
				break;
			default:
				return items;
		}
		if (held > 0)
		{
			items.put(offer.getItemId(), held);
		}
		if (coins > 0)
		{
			items.merge(ItemID.COINS, coins, Long::sum);
		}
		return items;
	}

	/** Reads every place the client already has, for when the plugin starts with the player logged in. */
	private void observeAll()
	{
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN, InventoryID.BANK, InventoryID.SEED_VAULT, InventoryID.LOOTING_BAG})
		{
			ItemContainer container = client.getItemContainer(id);
			if (container != null)
			{
				ledger.observe(store(id), contents(container));
			}
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			Map<Integer, Long> all = new HashMap<>();
			for (int i = 0; i < offers.length && i < GE_SLOTS; i++)
			{
				if (offers[i] != null)
				{
					exchange[i] = offerContents(offers[i]);
					exchange[i].forEach((id, q) -> all.merge(id, q, Long::sum));
				}
			}
			ledger.observe(Store.GRAND_EXCHANGE, all);
		}
	}

	private void clearExchange()
	{
		for (int i = 0; i < GE_SLOTS; i++)
		{
			exchange[i] = null;
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

	private void save()
	{
		if (profile == null || ledger == null || !unsaved)
		{
			return;
		}
		unsaved = false;
		configManager.setConfiguration(CodexLedgerConfig.GROUP, profile, LEDGER_KEY, gson.toJson(ledger));
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
		for (Day day : copy.getDays())
		{
			for (int id : day.getItems().keySet())
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
			SwingUtilities.invokeLater(() -> target.update(copy, shownNames, name, state, now));
		}
	}

	private void name(int itemId)
	{
		names.computeIfAbsent(itemId, id -> itemManager.getItemComposition(id).getName());
	}
}
