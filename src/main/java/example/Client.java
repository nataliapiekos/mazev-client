package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import example.domain.Request;
import example.domain.Response;
import example.domain.game.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static java.lang.Math.abs;

public class Client {
    private static final String HOST = "35.208.184.138";
    //private static final String HOST = "localhost";
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
            Player player;
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

                        render(cave, itemLocations, playerLocations);
                        final var cmd = new Request.Command(Direction.Left);
                        final var cmdJson = objectMapper.writeValueAsString(cmd);
                        writer.write(cmdJson);
                        writer.newLine();
                        writer.flush();
                        logger.info("Sent command: {}", cmd);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error in client operation", e);
        } finally {
            logger.info("Client exiting");
        }
    }

    private static void render(Cave cave, Collection<Response.StateLocations.ItemLocation> itemLocations, Collection<Response.StateLocations.PlayerLocation> playerLocations) {

//        for (int row = 0; row < cave.rows(); row++) {
//            for (int col = 0; col < cave.columns(); col++) {
//                if (cave.rock(row, col)) {
//                    System.out.print("X");
//                } else {
//                    System.out.print(" ");
//                }
//
//            }System.out.println();
//        }

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

        Map<Item.Gold, Location> goldMap = new HashMap<>(Map.of());
        for (final var entry : itemLocations) {
            final var location = entry.location();
            tbl[location.row() * caveCols + location.column()] = switch (entry.entity()) {
                case Item.Gold goldItem -> {
                    goldMap.put(goldItem, entry.location());
                    yield 'G';
                }

                case Item.Health ignored -> 'H';
            };
        }

        Location myLocation = null;
        for (final var entry : playerLocations) {
            final var location = entry.location();
            tbl[location.row() * caveCols + location.column()] = switch (entry.entity()) {
                case Player.Dragon ignored -> 'D';
                case Player.HumanPlayer humanPlayer -> {
                    if ("Natalia Piękoś".equals(humanPlayer.name())) {//zmienić na player
                        myLocation = entry.location();
                        yield 'M';
                    }
                    else {
                        yield 'P';
                    }
                }
            };
        }

        Location targetGold = findTargetGold(goldMap, myLocation);
        //Map<Item.Gold, Location> targetGold = findTargetGold(goldMap, myLocation);
        for (int j = 0; j < caveRows; j++) {
            for (int i = 0; i < caveCols; i++) {
                if (j * caveCols + i == targetGold.row() * caveCols + targetGold.column()) {
                    //if (j * caveCols + i == targetGold.values().getValue().row() * caveCols + targetGold.values().getValue().column()) {
                    tbl[j * caveCols + i] = 'T';
                }
                System.out.print(tbl[j * caveCols + i]);
            }
            System.out.println();
        }

    }

    private static Location findTargetGold(Map<Item.Gold, Location> goldMap, Location myLocation) {
        //private static Map<Item.Gold, Location> findTargetGold(Map<Item.Gold, Location> goldMap, Location myLocation) {
        int minDistance = Integer.MAX_VALUE;
        Location targetGold = null;
        //Map<Item.Gold, Location> targetGold = new HashMap<>();
        for (final var entry : goldMap.entrySet()) {
            final var location = entry.getValue();
            int distanceToGold = abs(myLocation.column() - location.column()) + abs(myLocation.row() - location.row());
            if (distanceToGold < minDistance) {
                minDistance = distanceToGold;
                targetGold = location;
                //targetGold.put(entry.getKey(),location);
            }
        }
        return targetGold;
    }

    private static HashMap<String, Integer> findPathToGold(Cave cave, Location myLocation, Location targetGold){

        HashMap<String, Integer> PathToGold = new HashMap<>();

        final var rowGold = targetGold.row();
        final var columnGold = targetGold.column();
        final var rowMe = myLocation.row();
        final var columnMe = myLocation.column();

        int minRow = Math.min(rowMe, rowGold);
        int minCol = Math.min(columnMe, columnGold);
        int maxRow = Math.max(rowMe, rowGold);
        int maxCol = Math.max(columnMe, columnGold);

        int moveUp = (rowGold - rowMe<0) ? rowMe - rowGold : 0;
        int moveDown = Math.max(rowGold-rowMe,0);
        int moveLeft = (columnGold - columnMe<0) ? columnMe - columnGold : 0;
        int moveRight = Math.max(columnGold-columnMe,0);

        // first path: up/down then left/right
        // Check if the move up/down is possible
        boolean firstPathFirstMove = true;
        for (int row = minRow+1; row < maxRow; row++ ) {
                if(cave.rock(row, columnMe)){//there is rock in the columnMe
                    firstPathFirstMove = false;
                    break;
                }
        }
        // Check if the move left/right is possible
        boolean firstPathSecondMove = true;
        if(firstPathFirstMove){
            for (int col = minCol+1; col < maxCol; col++ ) {
                if(cave.rock(rowGold, col)){//there is rock in the rowGold
                    firstPathSecondMove = false;
                    break;
                }
            }
        }

        // case when firstPath is possible
        if(firstPathSecondMove && firstPathFirstMove){
            moveUp = (rowGold - rowMe<0) ? rowMe - rowGold : 0;
            moveDown = Math.max(rowGold-rowMe,0);
            moveLeft = (columnGold - columnMe<0) ? columnMe - columnGold : 0;
            moveRight = Math.max(columnGold-columnMe,0);
            return PathToGold;
        }
        // second path: left/right then up/down
        // Check if the move left/right is possible
        boolean secondPathFirstMove = true;
        if(!firstPathSecondMove){
            for (int col = minCol+1; col < maxCol; col++ ) {
                if(cave.rock(rowMe, col)){//there is rock in the rowMe
                    secondPathFirstMove = false;
                    break;
                }
            }
        }

        // Check if the move up/right is possible
        boolean secondPathSecondMove = true;
        if(secondPathFirstMove){
            for (int row = minRow+1; row < maxRow; row++ ) {
                if(cave.rock(row, columnGold)){//there is rock in the ColumnGold
                    secondPathSecondMove = false;
                    break;
                }
            }
        }

        if(secondPathSecondMove && secondPathFirstMove){
            moveLeft = (columnGold - columnMe<0) ? columnMe - columnGold : 0;
            moveRight = Math.max(columnGold-columnMe,0);
            moveUp = (rowGold - rowMe<0) ? rowMe - rowGold : 0;
            moveDown = Math.max(rowGold-rowMe,0);
            return PathToGold;
        }
        return PathToGold;
    }


}

