import com.google.gson.Gson;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Tuple;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class ChapterTest02{

    public static void main(String[] args)
            throws InterruptedException {
        new ChapterTest02().run();
    }

    public void run() throws InterruptedException{
        Jedis conn = new Jedis("localhost", 15);
        testCacheRows(conn);
    }

    public void testShoppingCartCookies(Jedis conn)
            throws InterruptedException{
        System.out.println("\n----- testShoppingCartCookies -----");
        String token = UUID.randomUUID().toString();

        System.out.println("we will refresh our session...");
        updateToken(conn, token, "username", "itemX");
        System.out.println("add an item to the shopping cart");
        addToCart(conn, token, "itemY", 3);
        Map<String, String> r = conn.hgetAll("cart"+token);
        System.out.println("our shopping cart currently has:");
        for (Map.Entry<String, String> entry : r.entrySet()) {
            System.out.println(entry.getKey() + ":" + entry.getValue());
        }
        System.out.println();

        assert r.size() >= 1;

        System.out.println("let's clean out our sessions and carts");
        CleanFullSessionsThread thread = new CleanFullSessionsThread(0);
        thread.start();
        Thread.sleep(1000);
        thread.quit();
        Thread.sleep(2000);
        if (thread.isAlive()) {
            throw new RuntimeException("the clean full session thread is still alive");
        }

        r = conn.hgetAll("cart"+token);
        System.out.println("our shopping cart currently has:");
        for (Map.Entry<String, String> entry : r.entrySet()) {
            System.out.println(entry.getKey() + ":" + entry.getValue());
        }
        System.out.println();

        assert r.size() == 0;
    }

    public void testCacheRequest(Jedis conn){
        System.out.println("---- testCacheRequest ----");
        String token = UUID.randomUUID().toString();

        Callback callback = new Callback() {
            public String call(String request) {
                return "content for " + request;
            }
        };

        updateToken(conn,token,"username","itemX");
        String url = "http://test.com/?item=itemX";
        System.out.println("we are going to cache a simple request against" + url);
        String result = cacheRequest(conn, url, callback);
        System.out.println("we got initial content:\n" + result);

        System.out.println();

        assert result != null;

        System.out.println("To test that we've cached the request, we'll pass a bad callback");
        String result2 = cacheRequest(conn, result, null);
        System.out.println("We ended up getting the same response!\n" + result2);

        assert result2.equals(result);

        assert !canCache(conn, "http://test.com/");
        assert !canCache(conn, "http://test.com/?item=itemX&_=1234536");
    }

    public void testCacheRows(Jedis conn) throws InterruptedException{
        System.out.println("---- testCacheRows");
        System.out.println("First, let's schedule caching of itemX every 5 seconds");
        scheduleRowCache(conn, "itemX", 5);
        System.out.println("Our schedule looks like:");
        Set<Tuple> set = conn.zrangeWithScores("schedule:", 0, -1);
        for (Tuple tuple : set) {
            System.out.println(tuple.getElement() + "," + tuple.getScore());
        }
        assert set.size() != 0;

        System.out.println("we will start a caching data");

        CacheRowsThread thread = new CacheRowsThread();
        thread.start();

        Thread.sleep(1000);
        System.out.println("our cached data looks like:");
        String r = conn.get("inv:itemX");
        System.out.println(r);
        assert r != null;
        System.out.println();

        System.out.println("we will check again in 5 seconds...");
        Thread.sleep(5000);
        System.out.println("notice that the data has changed");
        r = conn.get("inv:itemX");
        System.out.println("the cache was cleared?" + (r == null));
        assert r == null;
    }

    public String checkToken(Jedis conn,String token){
        return conn.hget("login", token);
    }

    public void updateToken(Jedis conn, String token, String user, String item) {
        long timestamp = System.currentTimeMillis();
        conn.hset("login:", token, user);
        conn.zadd("recent:", timestamp, token);
        if (item != null) {
            conn.zadd("viewed" + token, timestamp, item);
            conn.zremrangeByRank("viewed" + token, 0, -26);
        }
    }

    public class CleanSessionsThread extends Thread {

        private Jedis conn;
        private int limit;
        private boolean quit;

        public CleanSessionsThread(int limit){
            this.conn = new Jedis("localhost", 15);
            this.limit = limit;
        }

        public void quit(){
            quit = true;
        }

        @Override
        public void run(){
            while (!quit){
                long size = conn.zcard("recent:");
                if(size<limit){
                    try{
                        sleep(1000);
                    }catch (InterruptedException e){
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }

                long endIndex = Math.min(size - limit, 100);
                Set<String> sessionSet = conn.zrange("recent:", 0, endIndex - 1);
                String[] sessions = sessionSet.toArray(new String[sessionSet.size()]);

                List<String> sessionKeys = new ArrayList<String>();
                for (String session:sessions){
                    sessionKeys.add("viewed:" + session);
                }

                conn.del(sessionKeys.toArray(new String[sessionKeys.size()]));
                conn.hdel("login:", sessions);
                conn.zrem("recent:", sessions);
            }
        }
    }

    public class CleanFullSessionsThread extends Thread {

        private Jedis conn;
        private int limit;
        private boolean quit;

        public CleanFullSessionsThread(int limit){
            this.conn = new Jedis("localhost", 15);
            this.limit = limit;
        }

        public void quit(){
            quit = true;
        }

        @Override
        public void run(){
            while (!quit){
                long size = conn.zcard("recent:");
                if(size<limit){
                    try{
                        sleep(1000);
                    }catch (InterruptedException e){
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }

                long endIndex = Math.min(size - limit, 100);
                Set<String> sessionSet = conn.zrange("recent:", 0, endIndex - 1);
                String[] sessions = sessionSet.toArray(new String[sessionSet.size()]);

                List<String> sessionKeys = new ArrayList<String>();
                for (String session:sessions){
                    sessionKeys.add("viewed:" + session);
                    sessionKeys.add("cart" + session);
                }

                conn.del(sessionKeys.toArray(new String[sessionKeys.size()]));
                conn.hdel("login:", sessions);
                conn.zrem("recent:", sessions);
            }
        }
    }

    public void addToCart(Jedis conn,String session,String item,int count){
        if (count<=0){
            conn.hdel("cart" + session, item);
        }else {
            conn.hset("cart" + session, item, String.valueOf(count));
        }
    }

    public String cacheRequest(Jedis conn, String request, Callback callback) {
        if (!canCache(conn, request)) {
            return callback != null ? callback.call(request) : null;
        }

        String pageKey = "cache:" + hashRequest(request);
        String content = conn.get(pageKey);

        if (content==null&&callback!=null){
            content = callback.call(request);
            conn.setex(pageKey, 300, content);
        }

        return content;
    }

    public boolean canCache(Jedis conn,String request){
        try {
            URL url = new URL(request);
            HashMap<String, String> params = new HashMap<String, String>();
            if (url.getQuery() != null) {
                for (String param : url.getQuery().split("&")) {
                    String[] pair = param.split("=", 2);
                    params.put(pair[0], pair.length == 2 ? pair[1] : null);
                }
            }

            String itemId = extractItemId(params);
            if (itemId == null || isDynamic(params)) {
                return false;
            }
            Long rank = conn.zrank("viewed", itemId);
            return rank != null && rank < 10000;
        }catch (MalformedURLException e){
            return false;
        }
    }

    public boolean isDynamic(HashMap<String, String> params) {
        return params.containsKey("_");
    }

    public String extractItemId(HashMap<String,String> params){
        return params.get("item");
    }

    public String hashRequest(String request){
        return String.valueOf(request.hashCode());
    }

    public interface Callback{
        public String call(String request);
    }

    public void scheduleRowCache(Jedis conn,String rowId,int delay){
        conn.zadd("delay:", delay, rowId);
        conn.zadd("schedule:", System.currentTimeMillis(), rowId);
    }

    public class CacheRowsThread extends Thread {
        private Jedis conn;
        private boolean quit;

        public CacheRowsThread(){
            this.conn = new Jedis("localhost",15);
        }

        public void quit(){
            quit = true;
        }

        @Override
        public void run() {
            Gson gson = new Gson();
            while (!quit){
                Set<Tuple> range = conn.zrangeWithScores("schedule:", 0, 0);
                Tuple next = range.size() > 0 ? range.iterator().next() : null;
                long now = System.currentTimeMillis() / 1000;
                if (next==null||next.getScore()>now) {
                    try {
                        sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                String rowId = next.getElement();

                double delay = conn.zscore("delay:", rowId);
                if (delay < 0) {
                    conn.zrem("delay:", rowId);
                    conn.zrem("schedule:", rowId);
                    continue;
                }

                Inventory row = Inventory.get(rowId);//data line
                conn.zadd("schedule:", now + delay, rowId);
                conn.set("inv" + rowId, gson.toJson(row));
            }
        }
    }

    public static class Inventory{
        private String id;
        private String data;
        private long time;

        private Inventory(String id) {
            this.id = id;
            this.data = "data to cache...";
            this.time = System.currentTimeMillis() / 1000;
        }

        public static Inventory get(String id) {
            return new Inventory(id);
        }
    }
}
