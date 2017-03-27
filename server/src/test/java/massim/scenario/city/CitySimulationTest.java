package massim.scenario.city;

import massim.config.TeamConfig;
import massim.protocol.messagecontent.Action;
import massim.protocol.messagecontent.RequestAction;
import massim.protocol.scenario.city.data.ActionData;
import massim.protocol.scenario.city.percept.CityStepPercept;
import massim.scenario.city.data.*;
import massim.scenario.city.data.facilities.*;
import massim.util.IOUtil;
import massim.util.Log;
import massim.util.RNG;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * (Integration) Testing (important aspects of) the City scenario.
 */
public class CitySimulationTest {

    /**
     * The shared simulation object (since creating a new one for each test is kind of expensive)
     */
    private static CitySimulation sim;

    private static int seed = 17;
    private static int agentsPerTeam = 6;
    private static int steps = 10000;
    private static int step = 0;

    /**
     * Sets up a blank initialized simulation that can be used for all tests.
     */
    @BeforeClass
    public static void setup() throws IOException {
        RNG.initialize(seed);

        sim = new CitySimulation();

        // create config
        JSONObject matchConf = IOUtil.readJSONObject("conf/QuickTest.json").getJSONArray("match").getJSONObject(0);
        // make agents really fast
        JSONObject roles = matchConf.getJSONObject("roles");
        roles.keySet().forEach(role -> {
            roles.getJSONObject(role).put("speed", 100000);
            roles.getJSONObject(role).put("load", 100000);
        });

        // setup teams
        Set<TeamConfig> teams = new HashSet<>(Arrays.asList(new TeamConfig("A"), new TeamConfig("B")));
        for(int i = 1; i <= agentsPerTeam; i++){
            for (TeamConfig team : teams) {
                team.addAgent("agent" + team.getName() + i, "1");
            }
        }

        sim.init(steps, matchConf, teams);
    }

    /**
     * Logs info before each test
     */
    @Before
    public void logInfo(){
        Log.log(Log.Level.NORMAL, "Current step: " + step);
    }

    /**
     * Make sure each test method uses a new step number.
     */
    @After
    public void incrementStep(){
        step++;
    }

    @Test
    public void actionIsPerceived() throws IOException {

        Map<String, Action> actions = buildActionMap();
        actions.put("agentA1", new Action("give", "agentA2", "item0", "1"));
        sim.preStep(step);
        sim.step(step++, actions);

        Map<String, RequestAction> percepts = sim.preStep(step);
        ActionData action = getPercept("agentA1", percepts).getSelfData().getLastAction();
        assert(action.getType().equals("give"));
        assert(action.getParams().get(0).equals("agentA2"));
        assert(action.getParams().get(1).equals("item0"));
        assert(action.getParams().get(2).equals("1"));
        assert(action.getResult().equals("failed_counterpart"));
        sim.step(step, buildActionMap());
    }

    @Test
    public void gotoWorks(){

        // determine a shop as goto target
        Shop shop = sim.getWorldState().getShops().iterator().next();

        // let all agents go somewhere
        sim.preStep(step);

        Map<String, Action> actions = buildActionMap();
        actions.put("agentA1", new Action("goto",
                String.valueOf(shop.getLocation().getLat()), String.valueOf(shop.getLocation().getLon())));
        actions.put("agentA2", new Action("goto", "resourceNode1"));
        actions.put("agentA3", new Action("goto", shop.getName()));

        sim.step(step, actions);

        // check results and new locations
        assert(sim.getWorldState().getEntity("agentA1").getLocation().equals(shop.getLocation()));
        assert(sim.getWorldState().getEntity("agentA2").getLastActionResult().equals("failed_unknown_facility"));
        assert(sim.getWorldState().getEntity("agentA3").getLocation().equals(shop.getLocation()));
    }

    @Test
    public void giveReceiveWorks(){

        // move one agent to another and give her some item
        Entity e4  = sim.getWorldState().getEntity("agentA4");
        Entity e5  = sim.getWorldState().getEntity("agentA5");
        Item item = sim.getWorldState().getItems().get(0);

        e4.setLocation(e5.getLocation());
        e4.clearInventory();
        e5.clearInventory();
        e4.addItem(item, 1);

        // assert preconditions
        assert(e4.getItemCount(item) == 1);
        assert(e5.getItemCount(item) == 0);

        // give and receive some items
        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA4", new Action("give", "agentA5", item.getName(), "1"));
        actions.put("agentA5", new Action("receive"));
        sim.step(step, actions);

        assert(e4.getItemCount(item) == 0);
        assert(e5.getItemCount(item) == 1);
    }

    @Test
    public void storeRetrieveWorks(){
        WorldState world = sim.getWorldState();
        Entity e2 = world.getEntity("agentA2");
        Item item = world.getItems().get(0);
        Storage storage = world.getStorages().iterator().next();

        e2.setLocation(storage.getLocation());
        e2.clearInventory();
        e2.addItem(item, 2);
        storage.removeStored(item, 100, "A");

        // preconditions
        assert(storage.getStored(item, "A") == 0);
        assert(e2.getItemCount(item) == 2);

        // store something
        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA2", new Action("store", item.getName(), "1"));
        sim.step(step, actions);

        assert(storage.getStored(item, "A") == 1);
        assert(e2.getItemCount(item) == 1);

        step++;

        // retrieve it
        sim.preStep(step);
        actions = buildActionMap();
        actions.put("agentA2", new Action("retrieve", item.getName(), "1"));
        sim.step(step, actions);

        assert(storage.getStored(item, "A") == 0);
        assert(e2.getItemCount(item) == 2);

        step++;

        // retrieve too much
        sim.preStep(step);
        sim.step(step, actions); // actions can be reused

        assert(storage.getStored(item, "A") == 0);
        assert(e2.getItemCount(item) == 2);
        assert(e2.getLastActionResult().equals("failed_item_amount"));

        step++;

        // store too much
        int fill = storage.getCapacity() / item.getVolume();
        storage.store(item, fill, "A");
        e2.addItem(item, 1);
        int carrying = e2.getItemCount(item);

        sim.preStep(step);
        actions = buildActionMap();
        actions.put("agentA2", new Action("store", item.getName(), "1"));
        sim.step(step, actions);

        assert(storage.getStored(item, "A") == fill);
        assert(e2.getItemCount(item) == carrying);
        assert(e2.getLastActionResult().equals("failed_capacity"));
    }

    @Test
    public void assembleWorks(){

        WorldState world = sim.getWorldState();
        Entity e1 = world.getEntity("agentA1");
        Entity e2 = world.getEntity("agentA2");
        Workshop workshop = world.getWorkshops().iterator().next();
        Optional<Item> optItem = world.getItems().stream() // find item that needs tools and materials
                .filter(item -> item.getRequiredItems().size() > 1 && item.getRequiredTools().size() > 0)
                .findAny();
        assert optItem.isPresent();
        Item item = optItem.get();

        e1.clearInventory();
        e2.clearInventory();
        e1.setLocation(workshop.getLocation());
        e2.setLocation(workshop.getLocation());

        List<Item> requiredItems = new ArrayList<>(item.getRequiredItems().keySet());
        e1.addItem(requiredItems.get(0), item.getRequiredItems().get(requiredItems.get(0)));
        for(int i = 1; i < requiredItems.size(); i++)
            e2.addItem(requiredItems.get(i), item.getRequiredItems().get(requiredItems.get(i)));

        // check assembly without tools

        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA1", new Action("assemble", item.getName()));
        actions.put("agentA2", new Action("assist_assemble", "agentA1"));
        sim.step(step, actions);

        assert e1.getLastActionResult().equals("failed_tools");
        assert e2.getLastActionResult().equals("failed_tools");

        step++;

        // check assembly with all requirements satisfied
        item.getRequiredTools().forEach(tool -> e1.addItem(tool, 1));

        sim.preStep(step);
        sim.step(step, actions);

        assert e1.getLastActionResult().equals("successful");
        assert e2.getLastActionResult().equals("successful");
        assert e1.getItemCount(item) == 1;
        item.getRequiredItems().keySet().forEach(req -> {
            assert e1.getItemCount(req) == 0;
            assert e2.getItemCount(req) == 0;
        });
        item.getRequiredTools().forEach(tool -> {
            assert e1.getItemCount(tool) == 1;
        });
    }

    @Test
    public void buyWorks(){
        WorldState world = sim.getWorldState();
        Entity e3 = world.getEntity("agentA3");
        long money = world.getTeam("A").getMoney();
        Shop shop = null;
        Item item = null;
        for(Shop s: world.getShops()){
            for(Item i: s.getOfferedItems()){
                if(s.getItemCount(i) > 0){
                    shop = s;
                    item = i;
                    break;
                }
            }
            if(item != null) break;
        }
        assert(shop != null && item != null);

        e3.clearInventory();
        e3.setLocation(shop.getLocation());

        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA3", new Action("buy", item.getName(), "1"));
        sim.step(step, actions);

        assert e3.getItemCount(item) == 1;
        assert world.getTeam("A").getMoney() == money - shop.getPrice(item);

        step++;

        // buy too many items
        sim.preStep(step);
        actions = buildActionMap();
        actions.put("agentA3", new Action("buy", item.getName(), "100"));
        sim.step(step, actions);

        assert e3.getItemCount(item) == 1;
        assert e3.getLastActionResult().equals("failed_item_amount");
        assert world.getTeam("A").getMoney() == money - shop.getPrice(item);
    }

    @Test
    public void dumpWorks(){
        WorldState world = sim.getWorldState();
        Entity e1 = world.getEntity("agentA1");
        Dump dump = world.getDumps().iterator().next();
        Item item = world.getItems().get(0);

        e1.clearInventory();
        e1.addItem(item, 7);
        e1.setLocation(dump.getLocation());

        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA1", new Action("dump", item.getName(), "4"));
        sim.step(step, actions);

        assert(e1.getItemCount(item) == 3);
    }

    @Test
    public void chargeWorks(){
        WorldState world = sim.getWorldState();
        Entity e1 = world.getEntity("agentA1");
        ChargingStation station = world.getChargingStations().iterator().next();

        e1.discharge();
        e1.setLocation(station.getLocation());

        assert e1.getCurrentBattery() == 0;

        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA1", new Action("charge"));
        sim.step(step, actions);

        assert e1.getCurrentBattery() == Math.min(e1.getRole().getMaxBattery(), station.getRate());
    }

    @Test
    public void rechargeWorks(){
        WorldState world = sim.getWorldState();
        Entity e2 = world.getEntity("agentA2");
        Shop shop = world.getShops().iterator().next();

        e2.setLocation(shop.getLocation()); // make sure the agent is not in a charging station
        e2.discharge();

        assert e2.getCurrentBattery() == 0;

        sim.preStep(step);
        Map<String, Action> actions = buildActionMap();
        actions.put("agentA2", new Action("recharge"));
        sim.step(step, actions);

        assert e2.getCurrentBattery() > 0; // actual amount is unpredictable
    }

    @Test
    public void gatherWorks(){
        WorldState world = sim.getWorldState();
        Entity e1 = world.getEntity("agentA1");
        ResourceNode node = world.getResourceNodes().iterator().next();
        Item item = node.getResource();

        e1.clearInventory();
        e1.setLocation(node.getLocation());

        assert e1.getItemCount(item) == 0;

        // check if the agent gathers at least once in 10 steps

        Map<String, Action> actions = buildActionMap();
        actions.put("agentA1", new Action("gather"));
        for(int i = 0; i < 10; i++){
            sim.preStep(step);
            sim.step(step, actions);
            step++;
        }

        assert e1.getItemCount(item) > 0;
    }

    @Test
    public void jobActionsWork(){
        WorldState world = sim.getWorldState();
        Storage storage = world.getStorages().iterator().next();
        Entity eA = world.getEntity("agentA1");
        Entity eB = world.getEntity("agentB1");
        Item item = world.getItems().get(0);
        long moneyA = world.getTeam("A").getMoney();
        long moneyB = world.getTeam("B").getMoney();
        int reward = 77777;

        eA.clearInventory();
        eB.clearInventory();
        eA.setLocation(storage.getLocation());
        eB.setLocation(storage.getLocation());
        eA.addItem(item, 3);
        storage.removeDelivered(item, 10000, "B");

        // check job posting

        Map<String, Action> actions = buildActionMap();
        actions.put("agentB1", new Action("post_job",
                                          String.valueOf(reward),
                                          "20",
                                          storage.getName(), item.getName(), "5"));

        sim.preStep(step);
        sim.step(step, actions);

        Optional<Job> job = world.getJobs().stream()
                .filter(j -> j.getPoster().equals("B"))
                .findAny();

        assert job.isPresent();
        String jobName = job.get().getName();

        step++;

        // check delivering (partial)

        actions = buildActionMap();
        actions.put("agentA1", new Action("deliver_job", jobName));

        sim.preStep(step);
        sim.step(step, actions);

        assert eA.getItemCount(item) == 0;
        assert eA.getLastActionResult().equals("successful_partial");

        step++;

        // check completion
        eA.addItem(item, 3);

        sim.preStep(step);
        sim.step(step, actions);

        assert eA.getItemCount(item) == 1;
        assert eA.getLastActionResult().equals("successful");
        assert world.getTeam("A").getMoney() == moneyA + reward;
        assert world.getTeam("B").getMoney() == moneyB - reward;
        assert storage.getDelivered(item, "B") == 5;

        step++;

        // retrieve delivery

        actions = buildActionMap();
        actions.put("agentB1", new Action("retrieve_delivered", item.getName(), "5"));

        sim.preStep(step);
        sim.step(step, actions);

        assert eB.getItemCount(item) == 5;
    }

    @Test
    public void bidWorks(){
        WorldState world = sim.getWorldState();
        Storage storage = world.getStorages().iterator().next();
        Item item = world.getItems().get(0);
        AuctionJob auction = new AuctionJob(999, storage, step + 1, step + 4, 2, 888);
        AuctionJob auction2 = new AuctionJob(999, storage, step + 1, step + 4, 2, 888);
        auction.addRequiredItem(item, 1);
        auction2.addRequiredItem(item, 1);
        world.addJob(auction);
        world.addJob(auction2);
        long moneyA = world.getTeam("A").getMoney();
        long moneyB = world.getTeam("B").getMoney();
        Entity eB = world.getEntity("agentB1");

        Map<String, Action> actions = buildActionMap();
        sim.preStep(step);
        sim.step(step, actions); // let auctions get names and be registered

        step++;

        actions.put("agentA1", new Action("bid_for_job", auction.getName(), "1000"));
        actions.put("agentB1", new Action("bid_for_job", auction2.getName(), "998"));
        sim.preStep(step);
        sim.step(step, actions);

        assert auction.getLowestBid() == null;
        assert auction2.getLowestBid() == 998;

        step++;

        actions = buildActionMap();
        actions.put("agentA1", new Action("bid_for_job", auction.getName(), "778"));
        sim.preStep(step);
        sim.step(step, actions);

        assert auction.getLowestBid() == 778;

        step++;

        // complete auction for team B

        eB.addItem(item, 1);
        eB.setLocation(storage.getLocation());

        actions = buildActionMap();
        actions.put("agentB1", new Action("deliver_job", auction2.getName()));
        sim.preStep(step);
        sim.step(step, actions);

        assert eB.getLastActionResult().equals("successful");

        step++;

        // check if team A paid the fine and B got the reward

        sim.preStep(step);
        sim.step(step, buildActionMap());

        assert world.getTeam("A").getMoney() == moneyA - auction.getFine();
        assert world.getTeam("B").getMoney() == moneyB + auction2.getLowestBid();
    }

    // TODO test facility creation/generation

    /**
     * @return a new action-map where each agent just skips
     */
    private static Map<String, Action> buildActionMap(){
        return sim.getWorldState().getAgents().stream()
                .collect(Collectors.toMap(ag -> ag, ag -> new Action("skip")));
    }

    /**
     * Checks if a request action contains the correct percept and returns it.
     * @param agent name of an agent
     * @param messages all req act messages received in preStep
     * @return the req act message of the agent cast to the correct percept
     */
    private static CityStepPercept getPercept(String agent, Map<String, RequestAction> messages){
        RequestAction reqAct = messages.get(agent);
        assert(reqAct != null && reqAct instanceof CityStepPercept);
        return (CityStepPercept) reqAct;
    }
}