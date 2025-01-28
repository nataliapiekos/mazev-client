package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.domain.Request;
import example.domain.Response;
import example.domain.game.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

public class Client {
    private static final String HOST = "35.208.184.138";
    private static final int PORT = 8080;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    public static void main(String[] args) {
        new Client().startClient();
    }

    public void startClient() {
        try (final var socket = new Socket(HOST, PORT); final var is = socket.getInputStream(); final var isr = new InputStreamReader(is); final var reader = new BufferedReader(isr); final var os = socket.getOutputStream(); final var osr = new OutputStreamWriter(os); final var writer = new BufferedWriter(osr)) {
            logger.info("Connected to server at {}:{}", HOST, PORT);
            {
                final var json = objectMapper.writeValueAsString(new Request.Authorize("DPcrSG2b"));
                writer.write(json);
                writer.newLine();
                writer.flush();
                logger.info("Sent command: {}", json);
            }

            Cave cave = null;
            Player player = null;
            Collection<Response.StateLocations.ItemLocation> itemLocations;
            Collection<Response.StateLocations.PlayerLocation> playerLocations;

            while (!Thread.currentThread().isInterrupted()) {
                final var line = reader.readLine();
                if (line == null) {
                    break;
                }

                final var response = objectMapper.readValue(line, Response.class);
                switch (response) {
                    case Response.Authorized authorized -> {
                        player = authorized.humanPlayer();
                        logger.info("authorized: {}", authorized);
                    }
                    case Response.Unauthorized unauthorized -> {
                        logger.error("unauthorized: {}", unauthorized);
                        return;
                    }
                    case Response.StateCave stateCave -> {
                        cave = stateCave.cave();
                        logger.info("cave: {}", cave);
                    }
                    case Response.StateLocations stateLocations -> {
                        itemLocations = stateLocations.itemLocations();
                        playerLocations = stateLocations.playerLocations();
                        logger.info("itemLocations: {}", itemLocations);
                        logger.info("playerLocations: {}", playerLocations);

                        Location myLocation = findMyLocation(playerLocations, player);
                        if (myLocation == null) {
                            logger.error("My location not found!");
                            return;
                        }

                        Location targetGold = findTargetGoldLocation(itemLocations, myLocation);
                        if (targetGold == null) {
                            logger.info("Target gold nor health location not found!");
                            //Maybe then fight
//                            final var randomDirection = Direction.values()[new Random().nextInt(Direction.values().length)];
//                            movePlayerPipeline(writer, randomDirection);
                        }
                        render(cave, itemLocations, playerLocations, player, targetGold);
                        List<Direction> pathToGold = findPathToTarget(cave, myLocation, targetGold, itemLocations, playerLocations, player);

                        if (pathToGold.isEmpty()) {
                            logger.info("No path to target gold! Moving in a random direction.");
                            final var randomDirection = Direction.values()[new Random().nextInt(Direction.values().length)];
                            movePlayerPipeline(writer, randomDirection);
                        } else {
                            movePlayerPipeline(writer, pathToGold.getFirst());
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error in client operation", e);
        } finally {
            logger.info("Client exiting");
        }
    }

    private static void render(Cave cave, Collection<Response.StateLocations.ItemLocation> itemLocations, Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player, Location targetGold) {

        final var caveRows = cave.rows();
        final var caveCols = cave.columns();
        final var gameTable = new char[caveRows * caveCols];

        for (int row = 0; row < caveRows; row++) {
            for (int col = 0; col < caveCols; col++) {
                if (cave.rock(row, col)) {
                    gameTable[row * caveCols + col] = 'X';
                } else {
                    gameTable[row * caveCols + col] = ' ';
                }
            }
        }

        for (final var entry : itemLocations) {
            final var location = entry.location();
            gameTable[location.row() * caveCols + location.column()] = switch (entry.entity()) {
                case Item.Gold ignored -> 'G';
                case Item.Health ignored -> 'H';
            };
        }

        for (final var entry : playerLocations) {
            final var location = entry.location();
            gameTable[location.row() * caveCols + location.column()] = switch (entry.entity()) {
                case Player.Dragon ignored -> 'D';
                case Player.HumanPlayer humanPlayer -> {
                    if (humanPlayer.equals(player)) {
                        yield 'M';
                    } else {
                        yield 'P';
                    }
                }
            };
        }

        if (targetGold != null) {
            gameTable[targetGold.row() * caveCols + targetGold.column()] = 'T';
        }

        for (int row = 0; row < caveRows; row++) {
            for (int col = 0; col < caveCols; col++) {
                System.out.print(gameTable[row * caveCols + col]);
            }
            System.out.println();
        }
    }

    private static Location findMyLocation(Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player) {
        return playerLocations.stream()
                .filter(entry -> entry.entity() instanceof Player.HumanPlayer humanPlayer && humanPlayer.equals(player))
                .map(Response.StateLocations.PlayerLocation::location)
                .findAny()
                .orElse(null);
    }

    // Find the closest gold (in Manhattan metric)
    // If there is no gold try to track the closest health
    private static Location findTargetGoldLocation(Collection<Response.StateLocations.ItemLocation> itemLocations, Location myLocation) {
        return itemLocations.stream()
                .filter(entry -> entry.entity() instanceof Item.Gold)
                .map(Response.StateLocations.ItemLocation::location)
                .min(Comparator.comparingInt(location -> abs(myLocation.column() - location.column()) + abs(myLocation.row() - location.row())))
                .orElse(findTargetHealthLocation(itemLocations, myLocation));
    }

    // Find the closest health (in Manhattan metric)
    private static Location findTargetHealthLocation(Collection<Response.StateLocations.ItemLocation> itemLocations, Location myLocation) {
        return itemLocations.stream()
                .filter(entry -> entry.entity() instanceof Item.Health)
                .map(Response.StateLocations.ItemLocation::location)
                .min(Comparator.comparingInt(location -> abs(myLocation.column() - location.column()) + abs(myLocation.row() - location.row())))
                .orElse(null);
    }

    // Find path to target (gold or health) omitting rocks and other players if possible
    private static List<Direction> findPathToTarget(Cave cave, Location myLocation, Location targetGold, Collection<Response.StateLocations.ItemLocation> itemLocations, Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player) {

        Map<Location, Integer> myLocationToCurrentDistances = new HashMap<>(); //g: cost of moving to a given point from my location
        Map<Location, Integer> myLocationToTargetDistances = new HashMap<>(); //f: cost of going to a target (gold or health) from my location
        Map<Location, Location> currentToPrevious = new HashMap<>(); //key: current location value: previous location

        Set<Location> notVisited = new HashSet<>();
        Set<Location> visited = new HashSet<>();

        myLocationToCurrentDistances.put(myLocation, 0);
        myLocationToTargetDistances.put(myLocation, distance(myLocation, targetGold));
        notVisited.add(myLocation);

        while (!notVisited.isEmpty()) {
            Location current = getCurrent(notVisited, myLocationToTargetDistances); // Not visited location which is closest to target
            if (current.equals(targetGold)) { // The player reached the target
                return reconstructPath(currentToPrevious, current);
            }

            notVisited.remove(current);
            visited.add(current);

            for (Direction direction : Direction.values()) {
                Location neighbour = getNeighborLocation(current, direction); // Find the location of the neighbour in a given direction

                // Check if the move is safe (other players or rocks)
                final var isOtherPlayer = isOtherPlayer(neighbour, playerLocations, player);
                if (isNotSafe(cave, neighbour, isOtherPlayer) || visited.contains(neighbour)) {
                    continue;
                }

                int neighbourDistance = myLocationToCurrentDistances.get(current) + 1;

                // Prioritize neighbour (reduce the cost) if there is health at that location
                if (isHealth(itemLocations, neighbour)) {
                    neighbourDistance -= 1;
                }

                if (!notVisited.contains(neighbour) || neighbourDistance < myLocationToCurrentDistances.getOrDefault(neighbour, Integer.MAX_VALUE)) {
                    notVisited.add(neighbour);
                    myLocationToCurrentDistances.put(neighbour, neighbourDistance);
                    myLocationToTargetDistances.put(neighbour, neighbourDistance + distance(neighbour, targetGold));
                    currentToPrevious.put(neighbour, current);
                }

            }

            // Case: My player surrounded by players or rocks
            // If rocks: random direction
            // If players: player who is closest to target gold
            if (isSurrounded(cave, current, playerLocations, player)) {
                final var optimalLocation = chooseOptimalLocation(current, playerLocations, player, targetGold);
                currentToPrevious.put(optimalLocation, current);
            }
        }

        return Collections.emptyList();
    }

    // Compute Manhattan distance
    private static int distance(Location start, Location end) {
        return Math.abs(start.row() - end.row()) + Math.abs(start.column() - end.column());
    }

    //Choose position who is the closest to target out of notVisited locations
    private static Location getCurrent(Set<Location> notVisited, Map<Location, Integer> totalDistances) {
        return notVisited.stream()
                .min(Comparator.comparingInt(location -> totalDistances.getOrDefault(location, Integer.MAX_VALUE)))
                .orElse(null);
    }

    private static List<Direction> reconstructPath(Map<Location, Location> currentToPrevious, Location current) {
        List<Direction> path = new ArrayList<>();

        while (currentToPrevious.containsKey(current)) {
            Location previous = currentToPrevious.get(current);
            path.add(getDirection(previous, current)); // consecutive step
            current = previous;
        }

        Collections.reverse(path);
        return path;
    }

    private static Direction getDirection(Location from, Location to) {
        if (to.row() < from.row()) {
            return Direction.Up;
        } else if (to.row() > from.row()) {
            return Direction.Down;
        } else if (to.column() < from.column()) {
            return Direction.Left;
        } else {
            return Direction.Right;
        }
    }

    private static Location getNeighborLocation(Location location, Direction direction) {
        return switch (direction) {
            case Up -> new Location(location.row() - 1, location.column());
            case Down -> new Location(location.row() + 1, location.column());
            case Left -> new Location(location.row(), location.column() - 1);
            case Right -> new Location(location.row(), location.column() + 1);
        };
    }

    // Check if there are other players next to me
    private static boolean isOtherPlayer(Location location, Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player) {
        return playerLocations.stream()
                .anyMatch(entry -> entry.entity() instanceof Player.HumanPlayer humanPlayer && !humanPlayer.equals(player) && entry.location().equals(location));
    }

    // Check if there is health at a neighbour location
    private static boolean isHealth(Collection<Response.StateLocations.ItemLocation> itemLocations, Location location) {
        return itemLocations.stream()
                .anyMatch(entry -> entry.location().equals(location) && entry.entity() instanceof Item.Health);
    }

    // Check if there are other players or rocks next to me
    private static boolean isNotSafe(Cave cave, Location location, boolean isOtherPlayer) {
        final var isRock = cave.rock(location.row(), location.column());
        return isRock || isOtherPlayer;

    }

    // Check if my player is surrounded by other player or rocks from every side
    private static boolean isSurrounded(Cave cave, Location myLocation, Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player) {
        return Arrays.stream(Direction.values())
                .map(direction -> getNeighborLocation(myLocation, direction))
                .allMatch(neighbor -> isNotSafe(cave, neighbor, isOtherPlayer(neighbor, playerLocations, player)));
    }

    // Find locations of players right next to me
    private static Map<Direction, Location> findAdjacentPlayers(Location myLocation, Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player) {
        Map<Direction, Location> adjacentPlayers = new HashMap<>();
        for (Direction direction : Direction.values()) {
            Location neighbor = getNeighborLocation(myLocation, direction);
            boolean isOtherPlayer = isOtherPlayer(neighbor, playerLocations, player);
            if (isOtherPlayer) {
                adjacentPlayers.put(direction, neighbor);
            }
        }
        return adjacentPlayers;
    }

    // Choose optimal location to move in case my player is surrounded
    private static Location chooseOptimalLocation(Location myLocation, Collection<Response.StateLocations.PlayerLocation> playerLocations, Player player, Location targetGold) {
        Map<Direction, Location> adjacentPlayers = findAdjacentPlayers(myLocation, playerLocations, player);

        // Case: My player is surrounded by NOT only rocks (at least one other player)
        // Choose player who is closest to target gold
        if (!adjacentPlayers.isEmpty()) {
            return adjacentPlayers.entrySet().stream()
                    .min(Comparator.comparingInt(entry -> distance(entry.getValue(), targetGold)))
                    .map(Map.Entry::getValue)
                    .orElse(getNeighborLocation(myLocation, Direction.Up)); // default if not found
        }

        // Case: My player is surrounded by rocks
        final var randomDirection = Direction.values()[new Random().nextInt(Direction.values().length)];
        logger.info("My player is surrounded by rocks. Moving in random direction.");
        return getNeighborLocation(myLocation, randomDirection);
    }


    private static void movePlayerPipeline(BufferedWriter writer, Direction direction) throws IOException {
        final var cmd = new Request.Command(direction);
        final var cmdJson = objectMapper.writeValueAsString(cmd);
        writer.write(cmdJson);
        writer.newLine();
        writer.flush();
        logger.info("Sent command: {}", cmd);
    }
}


