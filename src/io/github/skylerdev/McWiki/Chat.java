package io.github.skylerdev.McWiki;

import org.json.simple.JSONArray;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.List;

/**
 * Chat objects are wiki pages converted to chat format.
 *
 * @author skyler
 * @version 2018
 */
@SuppressWarnings("unchecked")
public class Chat {

    private final int cutoff;

    MCFont link;
    MCFont bold;
    MCFont italic;
    MCFont header2;
    MCFont header3;

    JSONArray chatJson;

    String domain;

    public Chat(ConfigHandler config, Document doc, String redirect, String articleURL, String domain) {

        link = config.getFont("a");
        bold = config.getFont("b");
        italic = config.getFont("i");
        header2 = config.getFont("h2");
        header3 = config.getFont("h3");

        cutoff = config.getInt("cutoff");

        this.domain = domain;

        chatJson = buildJson(doc);

        MCJson chatBottom = footer(articleURL);
        if (cutoff < chatJson.size()) {
            chatBottom.setText("\n >> Cutoff reached. [Open in web] << ");
        } else {
            chatBottom.setText(" >> End of article. [Open in web] << ");
        }

        // Chop chop
        for (int i = chatJson.size() - 1; i > cutoff; i--) {
            chatJson.remove(i);
        }

        MCJson chatTop = new MCJson("§d >> §6§l" + doc.title() + "§d << \n");
        chatJson.add(0, chatTop);
        chatJson.add(chatBottom);

    }

    public JSONArray getJson() {
        return chatJson;
    }

    private JSONArray buildJson(Document doc) {
        JSONArray json = new JSONArray();
        Elements main = doc.select("body > p, li");

        for (Element mainchild : main) {

            List<Node> inner = mainchild.childNodes();
            if (mainchild.is("li")) {
                json.add("- ");
            }
            json.add(parseInner(inner));
            json.add("\n");
        }

        return json;
    }

    private JSONArray parseInner(List<Node> inner) {
        JSONArray line = new JSONArray();
        line.add("");
        for (Node n : inner) {
            if (n instanceof Element) {
                Element e = (Element) n;

                if (e.is("a")) {
                    String linkto = e.attr("href");
                    MCJson a = new MCJson(e.text(), link);
                    if (linkto.contains(domain)) {
                        a.setClick("run_command", "/wiki " + linkto.substring(linkto.lastIndexOf("/") + 1));
                        a.setHover("show_text", "Click to show this article.");
                    } else {
                        a.setClick("open_url", linkto);
                        a.setHover("show_text", "External Link");
                    }
                    line.add(a);
                } else if (e.is("b")) {
                    line.add(new MCJson(e.text(), bold));
                } else if (e.is("i")) {
                    line.add(new MCJson(e.text(), italic));
                } else if (e.is("span")) {
                    line.add(new MCJson(e.text()));
                }
            }
            if (n instanceof TextNode) {
                line.add(new MCJson(((TextNode) n).text()));
            }
        }

        return line;

    }

    public MCJson footer(String url) {
        MCJson chatBottom = new MCJson();
        chatBottom.setClick("open_url", url);
        chatBottom.setHover("show_text", "Open this article in your browser.");
        chatBottom.setColor("light_purple");
        return chatBottom;
    }


}
