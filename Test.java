import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class Test {
    record AddressSuggestion(String displayName, double latitude, double longitude) {}

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = Map.of(
            "displayName", "test",
            "latitude", 1.0,
            "longitude", 2.0
        );
        AddressSuggestion s = mapper.convertValue(map, AddressSuggestion.class);
        System.out.println(s);
    }
}
