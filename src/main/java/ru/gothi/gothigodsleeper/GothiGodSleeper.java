package ru.gothi.gothigodsleeper;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GothiGodSleeper implements ModInitializer {
	public static final String MOD_ID = "gothigodsleeper";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Трекер игроков которые спят
	private final Set<UUID> sleepingPlayers = new HashSet<>();
	private int totalPlayers = 0;
	private boolean isNight = false;
	private long lastCheckTick = 0;
	private static final long CHECK_INTERVAL = 20L; // Проверяем каждую секунду

	@Override
	public void onInitialize() {
		LOGGER.info("GothiGodSleeper mod initialized!");

		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
	}

	private void onServerTick(MinecraftServer server) {
		long currentTick = server.getTicks();
		if (currentTick - lastCheckTick < CHECK_INTERVAL) {
			return;
		}
		lastCheckTick = currentTick;

		ServerWorld overworld = server.getOverworld();
		long timeOfDay = overworld.getTimeOfDay() % 24000;
		boolean nowIsNight = timeOfDay >= 12530 && timeOfDay < 23000;

		// ВАЖНО: Всегда обновляем счетчик игроков при проверке!
		updatePlayerCount(server); // <--- ПЕРЕМЕСТИТЕ ЭТУ СТРОКУ СЮДА

		if (!isNight && nowIsNight) {
			isNight = true;
			LOGGER.info("Ночь началась! Время: {}", timeOfDay);
			// updatePlayerCount(server); // <--- УДАЛИТЕ ОТСЮДА
		} else if (isNight && !nowIsNight) {
			isNight = false;
			sleepingPlayers.clear();
			LOGGER.info("Ночь закончилась. Время: {}", timeOfDay);
		}

		if (isNight) {
			checkSleepingPlayers(server);
		}
	}

	private void updatePlayerCount(MinecraftServer server) {
		// Всегда считаем игроков в Overworld, которые могут спать
		ServerWorld overworld = server.getOverworld();
		totalPlayers = (int) overworld.getPlayers().stream()
				.filter(player -> !player.isCreative() && !player.isSpectator())
				.count();

		// Более правильная очистка: оставляем только тех, кто онлайн на сервере
		sleepingPlayers.removeIf(uuid -> server.getPlayerManager().getPlayer(uuid) == null);
	}

	private void checkSleepingPlayers(MinecraftServer server) {
		if (totalPlayers <= 0) {
			return;
		}

		ServerWorld overworld = server.getOverworld();

		// Собираем спящих игроков в Overworld
		sleepingPlayers.clear();
		for (ServerPlayerEntity player : overworld.getPlayers()) {
			if (player.isSleeping()) {
				sleepingPlayers.add(player.getUuid());
			}
		}

		int sleepingCount = sleepingPlayers.size();

		if (sleepingCount > 0) {
			// Логируем информацию для отладки
			LOGGER.info("Спящих игроков: {}/{}, Процент: {}%",
					sleepingCount, totalPlayers, (double) sleepingCount / totalPlayers * 100);

			// Отправляем сообщение о прогрессе всем в Overworld
			Text progressMessage = Text.literal(String.format(
					"§7Спит §e%d§7 из §e%d§7 игроков (§e%.1f%%§7)",
					sleepingCount, totalPlayers, (double) sleepingCount / totalPlayers * 100
			));

			for (ServerPlayerEntity player : overworld.getPlayers()) {
				player.sendMessage(progressMessage, false);
			}

			// Рассчитываем процент
			double percentage = (double) sleepingCount / totalPlayers * 100;

			// Если достигнут порог в 25%
			if (percentage >= 25.0) {
				skipNight(server);
			}
		}
	}

	private void skipNight(MinecraftServer server) {
		ServerWorld overworld = server.getOverworld();

		// Пропускаем до утра
		overworld.setTimeOfDay(1000);

		// Будим всех игроков в Overworld
		for (ServerPlayerEntity player : overworld.getPlayers()) {
			if (player.isSleeping()) {
				player.wakeUp(false, false);
			}
		}

		// Отправляем сообщение всем игрокам на сервере
		Text message = Text.literal(String.format(
				"§aНочь пропущена! §e%d§a из §e%d§a игроков спали (≥25%%)",
				sleepingPlayers.size(), totalPlayers
		));

		server.getPlayerManager().broadcast(message, false);

		LOGGER.info("Night skipped! {} out of {} players were sleeping ({}%)",
				sleepingPlayers.size(), totalPlayers,
				(double) sleepingPlayers.size() / totalPlayers * 100);

		// Очищаем список спящих
		sleepingPlayers.clear();
		isNight = false;
	}
}