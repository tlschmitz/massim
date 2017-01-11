package massim;

import massim.config.ServerConfig;
import massim.config.TeamConfig;
import massim.scenario.AbstractSimulation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created in 2017.
 * MASSim massim.Server main class/entry point.
 * @author ta10
 */
public class Server {

    private Vector<String> commandQueue = new Vector<>();
    private ServerConfig config;

    private LoginManager loginManager;
    private AgentManager agentManager;

    public static void main(String[] args){
        Server server = new Server();

        // parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]){

                case "-conf":
                    try {
                        server.parseServerConfig(new JSONObject(new String(Files.readAllBytes(Paths.get(args[++i])),
                                StandardCharsets.UTF_8)));
                    } catch (java.io.IOException e) {
                        e.printStackTrace();
                        Log.log(Log.ERROR, "Could not read massim.config file.");
                        i--;
                    }
                    break;
                case "-confString":
                    try {
                        server.parseServerConfig(new JSONObject(args[++i]));
                    } catch (JSONException e) {
                        Log.log(Log.ERROR, "Passed configuration string invalid.");
                        i--;
                    }
                    break;
                default:
                    Log.log(Log.ERROR, "Unknown option: " + args[i]);
            }
        }

        // ask to choose massim.config file from conf directory
        if (server.config == null){
            File confDir = new File("conf");
            confDir.mkdirs();
            File[] confFiles = confDir.listFiles();
            if (confFiles == null || confFiles.length == 0) {
                Log.log(Log.NORMAL, "No massim.config files to load - exit MASSim.");
                System.exit(0);
            }
            else {
                Log.log(Log.NORMAL, "Choose a number:");
                for (int i = 0; i < confFiles.length; i++) {
                    Log.log(Log.NORMAL, i + " " + confFiles[i]);
                }
                Scanner in = new Scanner(System.in);
                Integer confNum = null;
                while (confNum == null) {
                    try {
                        confNum = Integer.parseInt(in.next());
                        if (confNum < 0 || confNum > confFiles.length - 1){
                            Log.log(Log.NORMAL, "No massim.config for that number, try again:");
                            confNum = null;
                        }
                    } catch (Exception e) {
                        Log.log(Log.NORMAL, "Invalid number, try again:");
                    }
                }
                try {
                    server.parseServerConfig(new JSONObject(new String( Files.readAllBytes(Paths.get(confFiles[confNum].toURI())))));
                } catch (IOException e) {
                    Log.log(Log.ERROR, "Could not read massim.config file, exiting MASSim");
                    System.exit(0);
                }
            }
        }

        server.go();
        server.close();
    }

    /**
     * Cleanup all threads etc.
     */
    private void close() {
        if (loginManager != null) loginManager.stop();
        if (agentManager != null) agentManager.stop();
    }

    /**
     * Starts server operation according to its configuration.
     */
    private void go(){

        // setup backend
        agentManager = new AgentManager(config.teams, config.agentTimeout);
        try {
            loginManager = new LoginManager(agentManager, config.port, config.backlog);
            loginManager.start();
        } catch (IOException e) {
            Log.log(Log.CRITICAL, "Cannot open server socket.");
            return;
        }

        // delay tournament start according to launch type
        if (config.launch.equals("key")){
            Log.log(Log.NORMAL,"Please press ENTER to start the tournament.");
            try {
                System.in.read();
            } catch (IOException ignored) {}
        }
        else if(config.launch.endsWith("s")){
            try{
                int interval = Integer.parseInt(config.launch.substring(0, config.launch.length() - 1));
                Log.log(Log.NORMAL, "Starting tournament in " + interval + " seconds.");
                Thread.sleep(interval * 1000);
            } catch(Exception e){
                Log.log(Log.ERROR, "Failed waiting, starting tournament now.");
            }
        }
        else{
            DateFormat timeFormat = new SimpleDateFormat("HH:mm");
            Calendar cal = Calendar.getInstance();
            Log.log(Log.NORMAL, "Current time is: " + cal.getTime().toString());
            try {
                //TODO review this part
                Calendar startDate = Calendar.getInstance();
                startDate.setTime(timeFormat.parse(config.launch));
                int hourOfDay = startDate.get(Calendar.HOUR_OF_DAY);
                int minute = startDate.get(Calendar.MINUTE);
                startDate.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DATE), hourOfDay, minute);
                Log.log(Log.NORMAL,"Starting time: " + startDate.getTime().toString());
                long time = startDate.getTimeInMillis();
                long diffTime = time - cal.getTimeInMillis();
                diffTime = Math.max(diffTime, 0);
                Log.log(Log.NORMAL, "The tournament will start in " + diffTime/1000 + " seconds.");
                Thread.sleep(diffTime);
            } catch (Exception e) {
                Log.log(Log.ERROR, "Could not parse start time. Starting tournament now.");
            }
        }

        // run matches according to tournament mode
        switch(config.tournamentMode){
            case "round-robin":
                // run a match for each team combination
                if (config.teamsPerMatch > config.teams.size()){
                    Log.log(Log.ERROR, "Not enough teams configured. Stopping MASSim now.");
                    System.exit(0);
                }
                int[] indices = new int[config.teamsPerMatch];
                for (int i = 0; i < indices.length; i++) indices[i] = i;
                boolean nextMatch = true;
                while (nextMatch){
                    Set<TeamConfig> matchTeams = new HashSet<>();
                    for (int index : indices) {
                        matchTeams.add(config.teams.get(index));
                    }

                    runMatch(matchTeams);
                    // TODO capture and process results

                    // determine the next team constellation
                    for (int i = indices.length - 1; i >= 0; i--) {
                        if (indices[i] < config.teams.size() - 1 - (indices.length - 1 - i)){
                            indices[i]++;
                            for (int j = i + 1; j < indices.length; j++){
                                indices[j] = indices[i] + (j - i);
                            }
                            break;
                        }
                        if (i == 0) nextMatch = false; // no team constellation left
                    }
                }
                break;
            case "manual":
                //TODO
                break;
            case "random":
                //TODO
                break;
            default:
                Log.log(Log.ERROR, "Invalid tournament mode: " + config.tournamentMode);
        }
    }

    /**
     * Runs a match for the given teams. Sim configuration is taken from the server config.
     * @param matchTeams a set of all teams to participate in the simulation
     */
    private void runMatch(Set<TeamConfig> matchTeams) {

        for (JSONObject simConfig: config.simConfigs){
            // create and run scenario instance with the given teams
            String className = simConfig.optString("scenarioClass", "");
            if (className.equals("")){
                Log.log(Log.ERROR, "No scenario class specified.");
                continue;
            }
            try {
                AbstractSimulation sim = (AbstractSimulation) AbstractSimulation.class.getClassLoader()
                                                                .loadClass("massim.scenario." + className)
                                                                .newInstance();
                Map<String, Percept> initialPercepts = sim.init(simConfig, matchTeams);
                agentManager.handleInitialPercepts(initialPercepts);
                for (int i = 0; i < simConfig.optInt("steps", 1000); i++){
                    Log.log(Log.NORMAL, "Simulation at step " + i);
                    Map<String, Percept> percepts = sim.preStep(i);
                    sim.setActions(agentManager.requestActions(percepts));
                    sim.step(i);
                }
                Map<String, Percept> finalPercepts = sim.finish();
                agentManager.handleFinalPercepts(finalPercepts);
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                e.printStackTrace();
                Log.log(Log.ERROR, "Could not load scenario class: " + className);
            }
        }
    }

    /**
     * Parses the given JSONObject into a new massim.config.ServerConfig object.
     * @param conf the JSONObject (configuration) holding a "server" JSONObject
     */
    private void parseServerConfig(JSONObject conf){
        config = new ServerConfig();
        JSONObject serverJSON = conf.optJSONObject("server");
        if (serverJSON == null) {
            Log.log(Log.ERROR, "No server object in configuration.");
            serverJSON = new JSONObject();
        }
        config.launch = serverJSON.optString("launch", "key");
        Log.log(Log.NORMAL, "Configuring launch type: " + config.launch);
        config.tournamentMode = serverJSON.optString("tournamentMode", "round-robin");
        Log.log(Log.NORMAL, "Configuring tournament mode: " + config.tournamentMode);
        config.teamSize = serverJSON.optInt("teamSize", 16);
        Log.log(Log.NORMAL, "Configuring team size: " + config.teamSize);
        config.teamsPerMatch = serverJSON.optInt("teamsPerMatch", 2);
        Log.log(Log.NORMAL, "Configuring teams per match: " + config.teamsPerMatch);
        config.port = serverJSON.optInt("port", 12300);
        Log.log(Log.NORMAL, "Configuring port: " + config.port);
        config.backlog = serverJSON.optInt("backlog", 10000);
        Log.log(Log.NORMAL, "Configuring backlog: " + config.backlog);
        config.agentTimeout = serverJSON.optInt("agentTimeout", 4000);
        Log.log(Log.NORMAL, "Configuring agent timeout: " + config.agentTimeout);

        // parse teams
        JSONObject teamJSON = conf.optJSONObject("teams");
        if (teamJSON == null) Log.log(Log.ERROR, "No teams configured.");
        else{
            teamJSON.keySet().forEach(name -> {
                TeamConfig team = new TeamConfig(name);
                config.teams.add(team);
                JSONObject accounts = teamJSON.optJSONObject(name);
                if (accounts != null){
                    accounts.keySet().forEach(agName -> {
                        team.addAgent(agName, accounts.getString(agName));
                        config.accounts.put(agName, accounts.getString(agName));
                    });
                }
            });
        }

        // parse matches
        JSONArray matchJSON = conf.optJSONArray("match");
        if (matchJSON == null){
            Log.log(Log.ERROR, "No match configured.");
            System.exit(0);
        }
        for(int i = 0; i < matchJSON.length(); i++){
            JSONObject simConfig = matchJSON.optJSONObject(i);
            if (simConfig != null) {
                config.simConfigs.add(simConfig);
            }
        }
    }
}