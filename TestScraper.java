import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

public class TestScraper {
    public static void main(String[] args) throws Exception {
        Document doc = Jsoup.connect("https://www.menicka.cz/brno.html").get();
        Elements restaurantElements = doc.select(".menicka_detail");
        for (var element : restaurantElements) {
            String name = element.select(".hlavicka .nazev a").text();
            String address = element.select(".hlavicka .ulice").text();
            System.out.println("Name: [" + name + "], Address: [" + address + "]");
        }
    }
}
