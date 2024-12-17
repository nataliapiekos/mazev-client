package example;

import com.fasterxml.jackson.core.JsonProcessingException;
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
        try (final var socket = new Socket(HOST, PORT);
             final var is = socket.getInputStream();
             final var isr = new InputStreamReader(is);
             final var reader = new BufferedReader(isr);
             final var os = socket.getOutputStream();
             final var osr = new OutputStreamWriter(os);
             final var writer = new BufferedWriter(osr)) {
            logger.info("Connected to server at {}:{}", HOST, PORT);

            {
                final var json = objectMapper.writeValueAsString(new Request.Authorize("1670"));
                writer.write(json);
                writer.newLine();
                writer.flush();
                logger.info("Sent command: {}", json);
            }

            Cave cave=null;
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
                            logger.info("Target gold location not found!");
                        }
                        render(cave, itemLocations, playerLocations, player, targetGold);
                        List<Direction> pathToGold = findPathToGold(cave, myLocation, targetGold);

                        if (pathToGold.isEmpty()) {
                            logger.info("No path to the target gold!");
                        } else {
                            for (Direction direction : pathToGold) {
                                switch (direction) {
                                    case Up -> moveUp(writer);
                                    case Down -> moveDown(writer);
                                    case Left -> moveLeft(writer);
                                    case Right -> moveRight(writer);
                                }
                            }
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
        final var tbl = new char[caveRows * caveCols];

        for (int row = 0; row < caveRows; row++) {
            for (int col = 0; col < caveCols; col++) {
                if (cave.rock(row, col)) {
                    tbl[row * caveCols + col] = 'X';
                } else {
                    tbl[row * caveCols + col] = ' ';
                }
            }
        }
        
        for (final var entry : itemLocations) {
            final var location = entry.location();
            tbl[location.row() * caveCols + location.column()] = switch (entry.entity()) {
                case Item.Gold ignored -> 'G';
                case Item.Health ignored -> 'H';
            };
        }
        
        for (final var entry : playerLocations) {
            final var location = entry.location();
            tbl[location.row() * caveCols + location.column()] = switch (entry.entity()) {
                case Player.Dragon ignored -> 'D';
                case Player.HumanPlayer humanPlayer -> {
                    if (humanPlayer.equals(player)) {
                        yield 'M';
                    }
                    else {
                        yield 'P';
                    }
                }
            };
        }

        if (targetGold != null) {
            tbl[targetGold.row() * caveCols + targetGold.column()] = 'T';
        }

        for (int row = 0; row < caveRows; row++) {
            for (int col = 0; col < caveCols; col++) {
                System.out.print(tbl[row * caveCols + col]);
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

    private static Location findTargetGoldLocation(Collection<Response.StateLocations.ItemLocation> itemLocations, Location myLocation) {
        Map<Item.Gold, Location> goldMap = itemLocations.stream()
                .filter(entry -> entry.entity() instanceof Item.Gold)
                .collect(Collectors.toMap(entry -> (Item.Gold) entry.entity(), Response.StateLocations.ItemLocation::location));

        return goldMap.values().stream()
                .min(Comparator.comparingInt(location ->
                        abs(myLocation.column() - location.column()) + abs(myLocation.row() - location.row())))
                .orElse(null);
    }

    private static List<Direction> findPathToGold(Cave cave, Location myLocation, Location targetGold){

        List<Direction> pathToGold = new ArrayList<>();
        // TODO
        return pathToGold;
    }

    private static void movePlayerPipeline(BufferedWriter writer, Direction direction) throws IOException {
        final var cmd = new Request.Command(direction);
        final var cmdJson = objectMapper.writeValueAsString(cmd);
        writer.write(cmdJson);
        writer.newLine();
        writer.flush();
        logger.info("Sent command: {}", cmd);
    }

    private static void moveUp(BufferedWriter writer) throws IOException {
        movePlayerPipeline(writer, Direction.Up);
    }

    private static void moveDown(BufferedWriter writer) throws IOException {
        movePlayerPipeline(writer, Direction.Down);
    }

    private static void moveLeft(BufferedWriter writer) throws IOException {
        movePlayerPipeline(writer, Direction.Left);
    }

    private static void moveRight(BufferedWriter writer) throws IOException {
        movePlayerPipeline(writer, Direction.Right);
    }
}


