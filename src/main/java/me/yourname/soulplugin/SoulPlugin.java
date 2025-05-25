package me.yourname.soulplugin; // 

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SoulPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerSouls> playerSouls = new HashMap<>();
    private File soulsFile;
    private FileConfiguration soulsConfig;

    // List.of()로 불변 리스트 생성
    private final List<String> categories = List.of("광물", "농작물", "암살", "사냥");
    private static final String PERMISSION_ADMIN = "soulplugin.admin";

    @Override
    public void onEnable() {
        // 설정 파일 생성 및 로드
        createSoulsConfig();
        loadSoulsData();

        // 이벤트 리스너 등록
        Bukkit.getPluginManager().registerEvents(this, this);

        // 명령어 등록
        var soulsCommand = getCommand("souls");
        if (soulsCommand != null) {
            soulsCommand.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) { // Java 16+ Pattern matching for instanceof
                    sender.sendMessage(ChatColor.RED + "플레이어만 사용할 수 있습니다.");
                    return true;
                }
                handleSoulCommand(player, args);
                return true;
            });
            soulsCommand.setTabCompleter((sender, command, alias, args) -> handleTabComplete(args));
        } else {
            getLogger().severe("'/souls' 명령어를 등록하지 못했습니다! plugin.yml 파일을 확인해주세요.");
        }

        // 1시간마다 자동 저장 (20 ticks * 60 seconds * 60 minutes)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            saveSoulsData();
            getLogger().info("영혼 데이터 자동 저장 완료.");
        }, 20L * 3600, 20L * 3600);

        getLogger().info("Soul Plugin 활성화됨! (Paper 1.21 Version)");
    }

    @Override
    public void onDisable() {
        saveSoulsData();
        getLogger().info("Soul Plugin 비활성화됨 - 데이터 저장 완료.");
    }

    private void createSoulsConfig() {
        soulsFile = new File(getDataFolder(), "souls.yml");
        if (!soulsFile.exists()) {
            if (!getDataFolder().exists()) {
                if (!getDataFolder().mkdirs()) { // 폴더 생성 실패 시 경고
                    getLogger().warning("플러그인 데이터 폴더 생성 실패: " + getDataFolder().getPath());
                }
            }
            try {
                if (!soulsFile.createNewFile()) { // 파일 생성 실패 시 (이미 존재할 수도 있지만, mkdirs 후엔 아닐 가능성)
                    getLogger().warning("souls.yml 파일 생성 실패 (이미 존재하거나 권한 문제).");
                }
            } catch (IOException e) {
                getLogger().severe("souls.yml 파일 생성 중 심각한 오류 발생!");
                e.printStackTrace();
                return; // 설정 파일 없이는 진행 불가
            }
        }
        soulsConfig = YamlConfiguration.loadConfiguration(soulsFile);
        // 새 파일이거나 구조 업데이트를 위해 한 번 저장 시도
        try {
            soulsConfig.save(soulsFile);
        } catch (IOException e) {
            getLogger().severe("souls.yml 초기 저장 중 오류 발생!");
            e.printStackTrace();
        }
    }

    private void loadSoulsData() {
        if (soulsConfig == null) {
            getLogger().warning("soulsConfig가 로드 중 null입니다. 재초기화를 시도합니다.");
            createSoulsConfig(); // 재초기화 시도
            if (soulsConfig == null) { // 여전히 null이면 로드 중단
                getLogger().severe("soulsConfig 초기화 실패. 데이터 로드를 중단합니다.");
                return;
            }
        }

        playerSouls.clear(); // 기존 데이터 초기화 후 로드

        var playersSection = soulsConfig.getConfigurationSection("players");
        if (playersSection != null) {
            for (String key : playersSection.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("잘못된 UUID 형식 '" + key + "'을(를) 스킵합니다.");
                    continue;
                }

                PlayerSouls souls = new PlayerSouls(); // 모든 카테고리 0으로 초기화됨
                for (String category : categories) {
                    int val = playersSection.getInt(key + "." + category, 0);
                    souls.setSoul(category, val);
                }
                playerSouls.put(uuid, souls);
            }
        }
        getLogger().info(playerSouls.size() + "명의 플레이어 영혼 데이터를 로드했습니다.");
    }

    private void saveSoulsData() {
        if (soulsConfig == null || soulsFile == null) {
            getLogger().severe("soulsConfig 또는 soulsFile이 null 상태입니다. 데이터 저장을 스킵합니다.");
            return;
        }

        // 기존 플레이어 데이터를 지우고 새로 저장 (오래된 데이터 방지)
        soulsConfig.set("players", null);

        for (Map.Entry<UUID, PlayerSouls> entry : playerSouls.entrySet()) {
            String playerPath = "players." + entry.getKey().toString();
            PlayerSouls souls = entry.getValue();
            for (String category : categories) {
                soulsConfig.set(playerPath + "." + category, souls.getSouls(category));
            }
        }

        try {
            soulsConfig.save(soulsFile);
        } catch (IOException e) {
            getLogger().severe("souls.yml 저장 중 오류 발생!");
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        Block block = event.getBlock();

        // 섬세한 손길 확인
        if (tool.hasItemMeta() && tool.getItemMeta().hasEnchant(Enchantment.SILK_TOUCH)) {
            player.sendMessage(ChatColor.RED + "[영혼] 섬세한 손길로는 영혼을 획득할 수 없습니다.");
            return;
        }

        // 농작물 성장 상태 확인
        String category = getCategoryForBlock(block.getType());
        if ("농작물".equals(category)) {
            if (block.getBlockData() instanceof Ageable ageable) { // 밀, 당근, 감자, 비트, 네더와트, 코코아(열매)
                if (ageable.getAge() != ageable.getMaximumAge()) {
                    return; // 다 자라지 않음
                }
            } else if (block.getType() == Material.MELON || block.getType() == Material.PUMPKIN) {
                // 수박, 호박은 Ageable이 아님
            } else {
                // 농작물 카테고리지만 Ageable도 아니고 수박/호박도 아닌 경우 (예: 잘못된 설정)
                return;
            }
        }

        int soulAmount = getSoulAmountForBlock(block.getType());

        if (soulAmount > 0 && category != null) {
            PlayerSouls souls = playerSouls.computeIfAbsent(player.getUniqueId(), k -> new PlayerSouls());
            souls.addSoul(category, soulAmount);
            player.sendMessage(ChatColor.GREEN + "[영혼] 숙련자의 " + category + " 영혼을 " + soulAmount + "만큼 획득하셨습니다.");
        }
    }

    @EventHandler
    public void onPlayerKill(PlayerDeathEvent event) {
        if (event.getEntity().getKiller() != null) { // 죽인 주체가 플레이어인지 확인
            Player killer = event.getEntity().getKiller();
            PlayerSouls souls = playerSouls.computeIfAbsent(killer.getUniqueId(), k -> new PlayerSouls());
            souls.addSoul("암살", 100); // 고정 수치
            killer.sendMessage(ChatColor.GREEN + "[영혼] 숙련자의 암살 영혼을 100만큼 획득하셨습니다.");
        }
    }

    @EventHandler
    public void onMonsterKill(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) return; // 몬스터인지 확인 및 캐스팅
        if (!(monster.getKiller() instanceof Player player)) return; // 죽인 주체가 플레이어인지 확인 및 캐스팅

        int level = getMonsterStrengthLevel(monster.getType()); // 몬스터 타입으로 레벨 결정
        int soulAmount = getSoulAmountByLevel(level);

        if (soulAmount > 0) {
            PlayerSouls souls = playerSouls.computeIfAbsent(player.getUniqueId(), k -> new PlayerSouls());
            souls.addSoul("사냥", soulAmount);
            player.sendMessage(ChatColor.GREEN + "[영혼] 숙련자의 사냥 영혼을 " + soulAmount + "만큼 획득하셨습니다.");
        }
    }

    private int getMonsterStrengthLevel(EntityType type) {
        // ThreadLocalRandom 사용 (동시성 환경에서 new Random()보다 약간의 이점)
        return switch (type) {
            // 하급 (레벨 1~3 랜덤, 영혼 10)
            case ZOMBIE, SKELETON, CREEPER, SPIDER, DROWNED, HUSK, SILVERFISH, SLIME, CAVE_SPIDER, ZOMBIE_VILLAGER -> ThreadLocalRandom.current().nextInt(1, 4); // bound is exclusive for upper
            // 중급 (레벨 3~5 랜덤, 영혼 20)
            case ENDERMAN, STRAY, WITCH, PILLAGER, VINDICATOR, BLAZE, GHAST, MAGMA_CUBE, PHANTOM, PIGLIN, ZOMBIFIED_PIGLIN, HOGLIN, ZOGLIN, ENDERMITE, SHULKER, VEX, GUARDIAN, BOGGED, BREEZE -> ThreadLocalRandom.current().nextInt(3, 6);
            // 상급 (레벨 4~6 랜덤, 영혼 30)
            case RAVAGER, EVOKER, WITHER_SKELETON, PIGLIN_BRUTE, ELDER_GUARDIAN -> ThreadLocalRandom.current().nextInt(4, 7);
            // 매우 강력 (레벨 7-8 랜덤, 영혼 40)
            case WARDEN -> ThreadLocalRandom.current().nextInt(7,9);
            // 정예 (레벨 300~500 랜덤, 영혼 50)
            case WITHER, ENDER_DRAGON -> ThreadLocalRandom.current().nextInt(300, 501);
            default -> 2; // 위에 명시되지 않은 기타 몬스터는 기본 레벨 2 (영혼 1~3)
        };
    }

    private int getSoulAmountByLevel(int level) {
        if (level <= 3) return randomBetween(1,3);      // 하급 (기본값 포함)
        if (level <= 5) return randomBetween(3, 5);      // 중급
        if (level <= 6) return randomBetween(4, 6);      // 상급
        if (level <= 8) return 50;      // 매우 강력 (워든 등)
        return randomBetween(300, 500);                      // 정예 (또는 레벨 8 초과, 위더/엔더드래곤)
    }

    private int getSoulAmountForBlock(Material material) {
        return switch (material) {
            // 광물 - 1 영혼
            case COAL_ORE, DEEPSLATE_COAL_ORE, IRON_ORE, DEEPSLATE_IRON_ORE, COPPER_ORE, DEEPSLATE_COPPER_ORE -> 1;
            // 광물 - 2 영혼
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, LAPIS_ORE, DEEPSLATE_LAPIS_ORE, REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> 2;
            // 광물 - 4 영혼
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> 4;
            // 광물 - 5 영혼
            case ANCIENT_DEBRIS -> 5;

            // 농작물 - 1 영혼 (다 자란 상태는 onBlockBreak에서 체크)
            case WHEAT, CARROTS, POTATOES, BEETROOTS -> 1;
            // 농작물 - 2 영혼
            case COCOA, MELON, PUMPKIN, NETHER_WART -> 2;
            default -> 0;
        };
    }

    private String getCategoryForBlock(Material material) {
        return switch (material) {
            case COAL_ORE, DEEPSLATE_COAL_ORE, IRON_ORE, DEEPSLATE_IRON_ORE, GOLD_ORE, DEEPSLATE_GOLD_ORE,
                 DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE, COPPER_ORE, DEEPSLATE_COPPER_ORE,
                 LAPIS_ORE, DEEPSLATE_LAPIS_ORE, REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE, ANCIENT_DEBRIS -> "광물";

            case WHEAT, CARROTS, POTATOES, BEETROOTS, COCOA, MELON, PUMPKIN, NETHER_WART -> "농작물";
            default -> null;
        };
    }

    private void handleSoulCommand(Player player, String[] args) {
        if (args.length == 0) { // 기본 /souls 명령어
            PlayerSouls souls = playerSouls.get(player.getUniqueId());
            if (souls == null) {
                player.sendMessage(ChatColor.RED + "[영혼] 영혼 수치를 찾을 수 없습니다. 활동을 시작하여 영혼을 모아보세요!");
                // 필요시 여기서 새 PlayerSouls 객체 생성 및 playerSouls 맵에 추가 가능
                // playerSouls.computeIfAbsent(player.getUniqueId(), k -> new PlayerSouls());
                // souls = playerSouls.get(player.getUniqueId());
                return;
            }
            player.sendMessage(ChatColor.AQUA + "--- " + player.getName() + "님의 영혼 수치 ---");
            for (String category : categories) {
                player.sendMessage(ChatColor.GREEN + "  " + category + ": " + ChatColor.WHITE + souls.getSouls(category));
            }
            return;
        }

        String subCommand = args[0].toLowerCase();

        if ("redeem".equals(subCommand)) { // 영혼 인출 명령어
            if (args.length != 3) {
                player.sendMessage(ChatColor.RED + "[영혼] 사용법: /souls redeem <분야> <수치>");
                return;
            }
            String categoryToRedeem = args[1];
            if (!categories.contains(categoryToRedeem)) { // 유효한 분야인지 확인
                player.sendMessage(ChatColor.RED + "[영혼] '" + categoryToRedeem + "'는 잘못된 분야입니다. 사용 가능 분야: " + String.join(", ", categories));
                return;
            }

            int amount;
            try {
                amount = Integer.parseInt(args[2]);
                if (amount <= 0) {
                    player.sendMessage(ChatColor.RED + "[영혼] 인출할 수치는 0보다 커야 합니다.");
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "[영혼] 수치를 정확한 숫자로 입력해주세요.");
                return;
            }

            PlayerSouls souls = playerSouls.computeIfAbsent(player.getUniqueId(), k -> new PlayerSouls()); // 없으면 생성
            if (!souls.canRedeem(categoryToRedeem, amount)) {
                player.sendMessage(ChatColor.RED + "[영혼] " + categoryToRedeem + " 영혼이 부족합니다. (현재: " + souls.getSouls(categoryToRedeem) + ")");
                return;
            }

            ItemStack item = new ItemStack(Material.NETHER_STAR); // 인출 아이템
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "숙련자의 " + categoryToRedeem + " 영혼 응축물");
                meta.setLore(List.of( // 아이템 설명
                        ChatColor.GRAY + "가치: " + amount + " " + categoryToRedeem + " 영혼",
                        ChatColor.DARK_PURPLE + "특별한 힘을 담고 있는 듯 하다."
                ));
                // meta.setCustomModelData(10001); // 리소스팩 사용 시 커스텀 모델 데이터
                item.setItemMeta(meta);
            }

            if (player.getInventory().firstEmpty() == -1) { // 인벤토리 공간 확인
                player.sendMessage(ChatColor.RED + "[영혼] 인벤토리에 공간이 부족하여 영혼을 인출할 수 없습니다.");
                return;
            }

            player.getInventory().addItem(item);
            souls.redeem(categoryToRedeem, amount);
            player.sendMessage(ChatColor.GREEN + "[영혼] " + amount + "의 " + categoryToRedeem + " 영혼을 인출하여 아이템을 획득하셨습니다.");
            return;
        }

        // OP 명령어 (권한 확인)
        if (!player.hasPermission(PERMISSION_ADMIN)) {
            player.sendMessage(ChatColor.RED + "[영혼] 이 명령어를 사용할 권한이 없습니다.");
            return;
        }

        if (args.length != 4) { // OP 명령어 형식 확인
            player.sendMessage(ChatColor.RED + "[영혼] 관리자 명령어 사용법:");
            player.sendMessage(ChatColor.YELLOW + "/souls set <플레이어> <분야> <수치>");
            player.sendMessage(ChatColor.YELLOW + "/souls add <플레이어> <분야> <수치>");
            player.sendMessage(ChatColor.YELLOW + "/souls remove <플레이어> <분야> <수치>");
            return;
        }

        // OP 명령어 공통 인자 처리
        String targetName = args[1];
        String categoryOp = args[2];
        int value;

        if (!categories.contains(categoryOp)) { // 유효한 분야인지 확인
            player.sendMessage(ChatColor.RED + "[영혼] '" + categoryOp + "'는 잘못된 분야입니다. 사용 가능 분야: " + String.join(", ", categories));
            return;
        }

        try {
            value = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "[영혼] 수치를 정확한 숫자로 입력해주세요.");
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName); // 온라인 플레이어만 대상
        if (target == null) {
            player.sendMessage(ChatColor.RED + "[영혼] 온라인 플레이어 '" + targetName + "'을(를) 찾을 수 없습니다.");
            return;
        }

        PlayerSouls targetSouls = playerSouls.computeIfAbsent(target.getUniqueId(), k -> new PlayerSouls());

        switch (subCommand) { // Enhanced switch
            case "set" -> {
                targetSouls.setSoul(categoryOp, value);
                player.sendMessage(ChatColor.GREEN + target.getName() + "님의 " + categoryOp + " 영혼을 " + value + "(으)로 설정했습니다.");
                if (target.isOnline() && !target.equals(player)) { // 대상에게 알림 (본인이 아닐 경우)
                    target.sendMessage(ChatColor.AQUA + "[영혼] 관리자에 의해 당신의 " + categoryOp + " 영혼이 " + value + "(으)로 설정되었습니다.");
                }
            }
            case "add" -> {
                targetSouls.addSoul(categoryOp, value);
                player.sendMessage(ChatColor.GREEN + target.getName() + "님의 " + categoryOp + " 영혼에 " + value + "만큼 추가했습니다.");
                if (target.isOnline() && !target.equals(player)) {
                    target.sendMessage(ChatColor.AQUA + "[영혼] 관리자에 의해 당신의 " + categoryOp + " 영혼이 " + value + "만큼 추가되었습니다.");
                }
            }
            case "remove" -> {
                targetSouls.removeSoul(categoryOp, value);
                player.sendMessage(ChatColor.GREEN + target.getName() + "님의 " + categoryOp + " 영혼을 " + value + "만큼 감소시켰습니다.");
                if (target.isOnline() && !target.equals(player)) {
                    target.sendMessage(ChatColor.AQUA + "[영혼] 관리자에 의해 당신의 " + categoryOp + " 영혼이 " + value + "만큼 감소되었습니다.");
                }
            }
            default -> player.sendMessage(ChatColor.RED + "[영혼] 알 수 없는 관리자 명령어입니다. (set, add, remove 사용)");
        }
    }

    private List<String> handleTabComplete(String[] args) {
        final List<String> completions = new ArrayList<>();
        String currentArg = args[args.length - 1].toLowerCase(); // 현재 입력 중인 인수

        if (args.length == 1) { // 첫 번째 인수 (하위 명령어)
            List<String> subcommands = new ArrayList<>(List.of("redeem"));
            // 권한 있는 사용자에게만 관리자 명령어 제안 (여기서는 간단히 모두 제안, 실제 실행은 권한 체크)
            subcommands.addAll(List.of("set", "add", "remove"));
            subcommands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(currentArg))
                    .forEach(completions::add);
        } else if (args.length == 2) { // 두 번째 인수
            String mainCmd = args[0].toLowerCase();
            if ("redeem".equals(mainCmd)) { // /souls redeem <분야>
                categories.stream()
                        .filter(cat -> cat.toLowerCase().startsWith(currentArg))
                        .forEach(completions::add);
            } else if (List.of("set", "add", "remove").contains(mainCmd)) { // /souls <op_cmd> <플레이어>
                Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(currentArg))
                        .forEach(completions::add);
            }
        } else if (args.length == 3) { // 세 번째 인수
            String mainCmd = args[0].toLowerCase();
            if ("redeem".equals(mainCmd)) { // /souls redeem <분야> <수치>
                List.of("10", "50", "100", "500").stream() // 수치 제안
                        .filter(s -> s.startsWith(currentArg))
                        .forEach(completions::add);
            } else if (List.of("set", "add", "remove").contains(mainCmd)) { // /souls <op_cmd> <플레이어> <분야>
                categories.stream()
                        .filter(cat -> cat.toLowerCase().startsWith(currentArg))
                        .forEach(completions::add);
            }
        } else if (args.length == 4) { // 네 번째 인수
            String mainCmd = args[0].toLowerCase();
            if (List.of("set", "add", "remove").contains(mainCmd)) { // /souls <op_cmd> <플레이어> <분야> <수치>
                List.of("10", "50", "100", "500", "1000").stream() // 수치 제안
                        .filter(s -> s.startsWith(currentArg))
                        .forEach(completions::add);
            }
        }
        // 부분 일치하는 것만 반환하도록 정렬 (선택 사항)
        // Collections.sort(completions);
        return completions;
    }

    // 플레이어별 영혼 관리 내부 클래스 (static으로 선언하여 외부 클래스 인스턴스 불필요)
    private static class PlayerSouls {
        private final Map<String, Integer> soulsByCategory = new HashMap<>();
        // PlayerSouls 클래스 내에서도 카테고리 목록을 인지하도록 함 (외부 클래스 categories 직접 참조도 가능)
        private static final List<String> KNOWN_CATEGORIES = List.of("광물", "농작물", "암살", "사냥");

        public PlayerSouls() {
            // 생성 시 모든 알려진 카테고리를 0으로 초기화
            KNOWN_CATEGORIES.forEach(cat -> soulsByCategory.put(cat, 0));
        }

        public int getSouls(String category) {
            return soulsByCategory.getOrDefault(category, 0); // 존재하지 않는 카테고리 요청 시 0 반환
        }

        public void setSoul(String category, int amount) {
            if (KNOWN_CATEGORIES.contains(category)) { // 알려진 카테고리인지 확인
                soulsByCategory.put(category, Math.max(amount, 0)); // 0 미만으로 설정 방지
            }
        }

        public void addSoul(String category, int amount) {
            if (amount <= 0) return; // 0 이하의 값은 추가하지 않음
            if (KNOWN_CATEGORIES.contains(category)) {
                soulsByCategory.put(category, getSouls(category) + amount);
            }
        }

        public void removeSoul(String category, int amount) {
            if (amount <= 0) return;
            if (KNOWN_CATEGORIES.contains(category)) {
                soulsByCategory.put(category, Math.max(getSouls(category) - amount, 0)); // 0 미만으로 감소 방지
            }
        }

        public boolean canRedeem(String category, int amount) {
            return amount > 0 && KNOWN_CATEGORIES.contains(category) && soulsByCategory.getOrDefault(category, 0) >= amount;
        }

        public void redeem(String category, int amount) {
            if (canRedeem(category, amount)) { // 인출 가능 여부 재확인
                removeSoul(category, amount);
            }
        }
    }
}