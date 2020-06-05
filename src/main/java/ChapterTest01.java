import redis.clients.jedis.Jedis;
import redis.clients.jedis.ZParams;

import java.util.*;

public class ChapterTest01 {

    private static final int ONE_WEEK_IN_SECONDS = 7*86400;
    private static final int VOTE_SCORE = 432;
    private static final int ARTICLES_PER_PAGE = 25;

    public static void main(String[] args) {
        new ChapterTest01().run();
    }


    public void run() {
        Jedis conn = new Jedis("localhost");
        conn.select(15);
        //Jedis conn = new Jedis("localhost",15);
        String articleId = postArticle(conn, "username", "a title", "www.google.com");
        System.out.println("we posted a new article with id:" + articleId);
        System.out.println("Its hash looks like");
        Map<String, String> articleData = conn.hgetAll("article:" + articleId);
        for (Map.Entry<String, String> entry : articleData.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }

        articleVote(conn, "other_user", "article:" + articleId);
        String votes = conn.hget("article:" + articleId, "votes");
        System.out.println("we vote for the article,it now has votes:" + votes);
        assert Integer.parseInt(votes) > 1;

        System.out.println("The currently highest-scoring articles are:");
        List<Map<String, String>> articles = getArticles(conn, 1);
        printArticles(articles);
        assert articles.size() >= 1;

        addGroup(conn, articleId, new String[]{"new group"});
        System.out.println("we added this article to a new group,other articles include");
        articles = getGroupArticles(conn, "new group", 1);
        printArticles(articles);
        assert articles.size() >= 1;
    }

    private void articleVote(Jedis conn, String user, String article) {
        long cutoff = (System.currentTimeMillis() / 1000) - ONE_WEEK_IN_SECONDS;
        if (conn.zscore("time:", article) < cutoff) {
            return;
        }

        String articleId = article.substring(article.indexOf(":") + 1);
        if (conn.sadd("voted" + articleId, user) == 1) {
            conn.zincrby("score:", VOTE_SCORE, article);
            conn.hincrBy(article, "votes", 1);
        }
    }

    private String postArticle(Jedis conn, String user, String title, String link) {
        String articleId = String.valueOf(conn.incr("article:"));

        String voted = "voted" + articleId;
        conn.sadd(voted, user);
        conn.expire(voted, ONE_WEEK_IN_SECONDS);

        long now = System.currentTimeMillis() / 1000;
        String article = "article:" + articleId;
        Map<String, String> articleData = new HashMap<String, String>();
        articleData.put("title", title);
        articleData.put("link", link);
        articleData.put("poster", user);
        articleData.put("now", String.valueOf(now));
        articleData.put("votes", "1");
        conn.hmset(article, articleData);

        conn.zadd("time:", now, article);
        conn.zadd("score:", now + VOTE_SCORE, article);

        return articleId;
    }

    private List<Map<String, String>> getArticles(Jedis conn, int page) {
        return getArticles(conn, page, "score:");
    }


    private List<Map<String, String>> getArticles(Jedis conn, int page, String order) {
        int start = (page - 1) * ARTICLES_PER_PAGE;
        int end = page * ARTICLES_PER_PAGE - 1;

        List<Map<String, String>> articles = new ArrayList<Map<String, String>>();
        Set<String> ids = conn.zrevrange(order, start, end);
        for (String id : ids) {
            Map<String, String> articleData = conn.hgetAll(id);
            articleData.put("id", id);
            articles.add(articleData);
        }
        return articles;
    }

    private void addGroup(Jedis conn, String articleId, String[] toAdd) {
        String article = "article:" + articleId;
        for (String group : toAdd) {
            conn.sadd("group:" + group, article);
        }
    }

    private List<Map<String, String>> getGroupArticles(Jedis conn, String group, int page) {
        return getGroupArticles(conn, group, page, "score:");
    }

    private List<Map<String, String>> getGroupArticles(Jedis conn, String group, int page, String order) {
        String key = order + group;
        if (!conn.exists(key)) {
            ZParams params = new ZParams().aggregate(ZParams.Aggregate.MAX);
            conn.zinterstore(key, params, "group:" + group, order);
            conn.expire(key, 60);
        }
        return getArticles(conn, page, order);
    }

    private void printArticles(List<Map<String, String>> articles) {
        for (Map<String, String> article : articles) {
            System.out.println("id:" + article.get("id"));
            for (Map.Entry<String, String> entry : article.entrySet()) {
                if (entry.getKey().equals("id")) {
                    continue;
                }
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }
        }
    }
}
