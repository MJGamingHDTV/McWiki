package io.github.skylerdev.McWiki;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * CommandWiki is called whenever a user runs /wiki.
 *
 * @author skyler
 * @version 2018
 */
public class CommandWiki implements CommandExecutor {

    private ConfigHandler config;

    private String lang;
    private boolean bookMode;
    private String api;
    private String wikiURL;


    public CommandWiki(McWiki plugin) {
        config = plugin.getConfigHandler();
        getConfigValues();

    }

    public void reload() {
        getConfigValues();
    }

    /**
     * This method assigns current values to use from this plugins ConfigHandler. It
     * is called whenever the plugin is refreshed.
     */
    private void getConfigValues() {
        lang = config.getLang();
        bookMode = config.getBook();
        String domain = config.getDomain();
        // If domain is default, then use custom language & default api
        if (domain.equals("minecraft.gamepedia.com") && !lang.equals("en")) {
            wikiURL = "https://minecraft-" + lang + ".gamepedia.com";
        } else {
            wikiURL = "https://" + domain;
        }

        api = wikiURL + "/" + "api.php";


    }

    @Override
    public boolean onCommand(final CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("wiki")) {
            if (args.length == 0) {
                return false;
            }

            final String article = conjoin(args, "_");
            final String title = conjoin(args, " ");
            final String articleURL = wikiURL + "/index.php?title=" + article;
            final ConsoleCommandSender consoleSender = Bukkit.getConsoleSender();

            asyncFetchArticle(articleURL, title, new DocumentGetCallback() {
                @Override
                public void onQueryDone(Document doc) {
                    if (doc == null) {
                        consoleSender.sendMessage(
                                "§cERROR: Null pointer: Fetched successfully, but document returned was null. Check what your last command was and report to github.com/skylerdev");
                        return;
                    }

                    if (doc.baseUri().startsWith("ERROR404")) {
                        sender.sendMessage("§cArticle not found. Check the article name and try again.");
                        return;
                    }

                    if (doc.baseUri().startsWith("ERROR")) {

                        switch (doc.baseUri()) {
                            case "ERRORIO":
                                consoleSender.sendMessage("§cERROR: IOException. Double check your config.");
                                break;
                            case "ERRORDC":
                                consoleSender.sendMessage("§eERROR: IOException on retrieval of article (no connection?).  Double check your config. ");
                                break;
                            case "ERRORPE":
                                consoleSender.sendMessage("§cERROR: ParserException: Recieved malformed JSON when trying to retrieve article name.");
                                break;
                            case "ERROR404":
                                consoleSender.sendMessage("§cArticle not found. Check the article name and try again.");
                                return;
                            case "ERRORNULLDOC":
                                consoleSender.sendMessage(
                                        "§cERROR: Null pointer: Null pointer encountered while trying to fetch document. This... should never happen. Double check your config.");
                                break;
                            case "ERRORMF":
                                consoleSender.sendMessage("§cERROR: Malformed URL. Please check your MCWIKI config and try again.");
                                break;
                            case "ERROR999":
                                consoleSender.sendMessage("§cERROR: No connection to the internet.");
                                break;
                            default:
                                consoleSender.sendMessage("§cFATAL ERROR: HTTP status code " + doc.baseUri().substring(5) + ".");
                                return;
                        }
                        sendConsole("[McWiki]: error details from last command: " + doc.text());
                        return;
                    }

                    final String redirect = doc.getElementById("redirect").text();

                    // if mw-parser-output exists, use that instead (newer MediaWiki's use this)
                    Elements optionalOutput = doc.getElementsByClass("mw-parser-output");
                    if (!optionalOutput.isEmpty()) {
                        doc.selectFirst("body").html(optionalOutput.html());
                    }

                    doc.getElementsByTag("div").remove();
                    doc.getElementsByTag("table").remove();

                    if (bookMode) {
                        Book book = new Book(config, doc, redirect, articleURL, wikiURL);
                        List<String> pages = book.getPages();
                        showBook(pages, sender.getName());
                    } else {
                        Chat chat = new Chat(config, doc, redirect, articleURL, wikiURL);
                        JSONArray chatJson = chat.getJson();

                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                "tellraw " + sender.getName() + " " + chatJson.toString());
                    }
                }
            });

            return true;
        }
        return false;

    }

    /**
     * Fetches the article from the web, asynchronously.
     *
     * @param url      url of article to fetch.
     * @param callback callback to implement when done fetching.
     */
    private void asyncFetchArticle(final String url, final String title, final DocumentGetCallback callback) {
        // async run
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
            @Override
            public void run() {

                StringBuilder result = new StringBuilder();
                try {
                    URL apiurl = new URL(api + "?action=query&titles=" + title.replace(" ", "_") + "&redirects=true&format=json");
                    HttpURLConnection conn = (HttpURLConnection) apiurl.openConnection();
                    conn.setRequestMethod("GET");
                    BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    while ((line = rd.readLine()) != null) {
                        result.append(line);
                    }
                    rd.close();

                } catch (final IOException e) {

                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {
                            e.getMessage();
                            callback.onQueryDone(new Document("ERRORDC").appendText((e.toString() + api + title)).ownerDocument());
                        }
                    });
                }

                String newTitle = title;
                String redirectedFrom = " ";
                JSONParser parser = new JSONParser();
                try {

                    JSONObject json = (JSONObject) parser.parse(result.toString());

                    // parse JSON object into title and redirect, if exists
                    JSONObject query = (JSONObject) json.get("query");
                    JSONArray normalized = (JSONArray) query.get("normalized");
                    JSONArray redirects = (JSONArray) query.get("redirects");
                    if (redirects != null) {
                        JSONObject redirectsActual = (JSONObject) redirects.get(0);
                        redirectedFrom = (String) redirectsActual.get("from");
                        newTitle = (String) redirectsActual.get("to");
                    } else if (normalized != null) {
                        JSONObject normalizedActual = (JSONObject) normalized.get(0);
                        newTitle = (String) normalizedActual.get("to");
                    }
                } catch (final ParseException e) {

                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {
                            callback.onQueryDone(new Document("ERRORPE").appendText((e.toString())).ownerDocument());
                        }
                    });

                } catch (final NullPointerException e) {

                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {
                            callback.onQueryDone(new Document("ERRORNULLDOC").appendText((e.toString())).ownerDocument());
                        }
                    });

                }

                try {

                    final Document doc = Jsoup.connect(wikiURL + "/index.php").data("action", "render").data("title", title).get();
                    doc.appendElement("div").attr("id", "redirect").text(redirectedFrom);
                    doc.title(newTitle);

                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {
                            callback.onQueryDone(doc);

                        }
                    });

                } catch (final HttpStatusException e) {
                    // Http Status error.
                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {

                            callback.onQueryDone(new Document("ERROR" + e.getStatusCode()));

                        }
                    });
                } catch (final MalformedURLException e) {
                    //URL is bad.
                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {
                            callback.onQueryDone(new Document("ERRORMF").appendText((e.toString())).ownerDocument());
                        }
                    });
                } catch (final IOException e) {
                    //Connection error.
                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("McWiki"), new Runnable() {
                        @Override
                        public void run() {

                            callback.onQueryDone(new Document("ERRORDC").appendText((e.toString())).ownerDocument());
                        }
                    });
                }

            }
        });
    }

    /**
     * Sends message to console.
     */
    private void sendConsole(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /**
     * Shows book using BookUtil reflection class.
     *
     * @param pages      The pages of the book to display in List<String>
     * @param playername The name of the player
     */
    private void showBook(List<String> pages, String playername) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("");
        meta.setAuthor("");
        BookUtil.setPages(meta, pages);
        book.setItemMeta(meta);
        BookUtil.openBook(book, Bukkit.getPlayer(playername));
    }

    /**
     * Helper method, joins args to make String.
     *
     * @param String[] Array of strings to conjoin with a value
     */
    private String conjoin(String[] args, String value) {
        String a = args[0].toLowerCase();
        for (int i = 1; i < args.length; i++) {
            a = a + value + args[i];
        }
        return a;
    }

}
