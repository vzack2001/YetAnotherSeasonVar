/*  original serviio plugin for seasonvar.ru
    https://forum.serviio.org/viewtopic.php?t=23717
*/
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.apache.http.NameValuePair
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.message.BasicNameValuePair
import org.serviio.library.metadata.MediaFileType
import org.serviio.library.online.*
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import static java.lang.System.currentTimeMillis

class Plugin extends WebResourceUrlExtractor {
    static final String hostname = "seasonvar.ru"
    private static final Map<String, String> headers = [
        "User-Agent": "Mozilla/5.0 (iPad; CPU OS 9_1 like Mac OS X) \
AppleWebKit/601.1.46 (KHTML, like Gecko) \
Version/9.0 Mobile/13B143 Safari/601.1",
        "Accept": "*/*",
        "Accept-Language": "ru-RU",
    ]
    private static final Map<String, String> cookies = [
        "playerHtml": "true",
        "svid1": "1428617_bdaa2d32cd6d1f864cba566da5949672"
    ]
    private final HTTP connector = new HTTP(headers, [(hostname): cookies])

    static Map<String, String> getQueryParams(URL url) {
        List<NameValuePair> params = URLEncodedUtils.parse(url.toURI(), "UTF-8")
        return params.collectEntries {
            String name = URLDecoder.decode(it.name, "UTF-8").toLowerCase()
            String value = URLDecoder.decode(it.value, "UTF-8").toLowerCase()
            if (name == "translation") name = "t" // that key used the original plugin
            return [(name): value]}
    }

    /**
     * Called once for the whole feed.
     * For each feed which needs a plugin Serviio tries to match all available plugins to the feed's URL by
     * calling this method and uses the first plugin that returns true. Use of regular expression is
     * recommended.
     *
     * @param url URL of the whole feed, as entered by the user
     * @return true if the feed's items can be processed by this plugin
     */
    @Override
    boolean extractorMatches(URL url) {
        return url.host == hostname
    }

    /**
     * @return the version of this plugin. Defaults to “1” if the method is not implemented.
     */
    @Override
    int getVersion() {
        return 6
    }

    /**
     * @return the name of this plugin. Is mostly used for logging and reporting purposes.
     */
    @Override
    String getExtractorName() {
        return "YetAnotherSeasonVarPlugin v${getVersion()}"
    }

    private IndexPageParserInterface getIndexPageParser(URL url) {
        String html = this.connector.GET(url).text
        IndexPageParserInterface indexPageParser
        try {
            indexPageParser = new IndexPageParser(html)
        } catch (IllegalStateException ignore) { // possibly blocked
            indexPageParser = new BlockedIndexPageParser(html)
        }
        return indexPageParser
    }

    private PlayerPageParser getPlayerPageParser(Map<String, String> params, String userPreferredTranslation) {
        return new PlayerPageParser(
                this.connector.POST("http://seasonvar.ru/player.php", params).text,
                userPreferredTranslation
        )
    }

    private WebResourceContainer parseAllNextSeasonsOfSerial(URL url, String userPreferredTranslation) {
        IndexPageParserInterface currentSeasonPageParser = getIndexPageParser(url)
        List<String> allSeasons = currentSeasonPageParser.allSeasons
        int currentSeasonNumber = allSeasons.findIndexOf {it.contains(url.path)}
        List<String> currentAndNextSeasonsUrls = allSeasons[currentSeasonNumber..allSeasons.size() - 1]
        List<IndexPageParserInterface> allIndexPages = []
        currentAndNextSeasonsUrls.eachWithIndex { String item, int idx ->
            IndexPageParserInterface indexPageParser
            if (idx != 0) {
                indexPageParser = getIndexPageParser(new URL(url.protocol, url.host, item))
            } else {
                indexPageParser = currentSeasonPageParser
            }
            allIndexPages.add(indexPageParser)
        }
        return new WebResourceContainer(
                title: "${currentSeasonPageParser.title} \u0438 \u043f\u043e\u0441\u043b\u0435\u0434\u0443\u044e\u0449\u0438\u0435",
                thumbnailUrl: "http://cdn.seasonvar.ru/oblojka/large/${currentSeasonPageParser.id}.jpg",
                items: (List<WebResourceItem>) allIndexPages.collect { IndexPageParserInterface indexPage ->
                    PlayerPageParser playerPageParser = getPlayerPageParser(indexPage.asMap(), userPreferredTranslation)
                    List<Map<String, String>> series = (List<Map<String, String>>) this.connector.GET(new URL(
                            url.protocol,
                            url.host,
                            playerPageParser.playlist
                    )).json
                    series.collect { item ->
                        new WebResourceItem(
                                title: /${indexPage.seasonId} \u0441\u0435\u0437\u043e\u043d ${item["title"].replace("<br>", " ")}/,
                                additionalInfo: [
                                        "link": item["file"]
                                ]
                        )
                    }
                }.flatten()
        )
    }

    private WebResourceContainer parseSingleSeasonOfSerial(URL url, String userPreferredTranslation) {
        IndexPageParserInterface indexPageParser = getIndexPageParser(url)
        PlayerPageParser playerPageParser = getPlayerPageParser(indexPageParser.asMap(), userPreferredTranslation)
        List<Map<String, String>> series = (List<Map<String, String>>) this.connector.GET(new URL(
                url.protocol,
                url.host,
                playerPageParser.playlist
        )).json
        return new WebResourceContainer(
                title: indexPageParser.title,
                thumbnailUrl: "http://cdn.seasonvar.ru/oblojka/large/${indexPageParser.id}.jpg",
                items: series.collect { Map<String, String> item ->
                    new WebResourceItem(
                            title: item["title"].replace("<br>", " "),
                            additionalInfo: [
                                    "link": item["file"]
                            ]
                    )
                }
        )
    }

    /**
    * Performs the extraction of basic information about the resource.
    * If the object cannot be constructed the method should return null or throw an exception.
    *
    * @param url URL of the resource to be extracted. The plugin will have to get the contents on the URL itself.
    * @param i Max. number of items the user prefers to get back or -1 for unlimited. It is
    * up to the plugin designer to decide how to limit the results (if at all).
    * @return an instance of org.serviio.library.online.WebResourceContainer.
    */
    @Override
    protected WebResourceContainer extractItems(URL url, int maxItems) {
        String userPreferredTranslation = null
        boolean loadNextSeasons = false
        if (url.getQuery()) {
            Map<String, String> userParams = getQueryParams(url)
            url = new URL(url.protocol, url.host, url.path)  // cleanup user's query
            if (userParams.containsKey("t")) userPreferredTranslation = userParams.get("t")
            if (userParams.containsKey("next")) loadNextSeasons = userParams.get("next") == "true"
        }
        WebResourceContainer resourceContainer
        if (loadNextSeasons) {
            resourceContainer = parseAllNextSeasonsOfSerial(url, userPreferredTranslation)
        } else {
            resourceContainer = parseSingleSeasonOfSerial(url, userPreferredTranslation)
        }
        return resourceContainer
    }

    /**
     * This method is called once for each item included in the created WebResourceContainer.
     * Performs the actual extraction of content information using the provided information.
     * If the object cannot be constructed the method should return null or throw an exception.
     *
     * @param webResourceItem an instance of org.serviio.library.online.WebResourceItem, as created in
     * extractItems() method.
     * @param preferredQuality includes value (HIGH, MEDIUM, LOW) of enumeration
     * org.serviio.library.online.PreferredQuality. It should be taken into consideration if the
     * online service offers multiple quality-based renditions of the content.
     * @return an instance of org.serviio.library.online.ContentURLContainer.
     */
    @Override
    protected ContentURLContainer extractUrl(WebResourceItem webResourceItem, PreferredQuality preferredQuality) {
        return new ContentURLContainer(
            fileType: MediaFileType.VIDEO,
            thumbnailUrl: webResourceItem.additionalInfo.thumbnailUrl,
            contentUrl: webResourceItem.additionalInfo.link,
            userAgent: headers["User-Agent"]
        )
    }
}

class PageParser {
    private final String html

    PageParser(String html) {
        this.html = html
    }

    protected Matcher match(String pattern) {
        Matcher matcher = this.html =~ pattern
        matcher.find()
        return matcher
    }

    protected static Matcher match(String text, String pattern) {
        Matcher matcher = text =~ pattern
        matcher.find()
        return matcher
    }
}

interface IndexPageParserInterface {
    String getType()
    String getTitle()
    String getSeasonId()
    String getId()
    String getSerial()
    String getSecure()
    String getTime()
    List<String> getAllSeasons()
    Map<String, String> asMap()
}

class IndexPageParser extends PageParser implements IndexPageParserInterface {
    private final String type = "html5"
    private final String title
    private final String seasonId
    private final String id
    private final String serial
    private final String secure
    private final String time
    private List<String> allSeasons

    IndexPageParser(String html) {
        super(html)
        this.title = parseTitle()
        this.seasonId = parseCurrentSeasonId()
        this.id = parseId()
        this.serial = parseSerial()
        this.secure = parseSecure()
        this.time = parseTime()
    }

    protected String parseTitle() {
        Matcher matcher = match(/(?s)<h1\s+class="pgs-sinfo-title".*?>[\u0410-\u044f]+\s+(.*?)\s+[\u0410-\u044f]+<\/h1>/)
        return matcher.group(1).replaceAll(/\s+/, " ")
    }

    protected String parseCurrentSeasonId() {
        try {
            Matcher matcher = match(/(?s)<h1\s+class="pgs-sinfo-title".*?([0-9]+)\s+[\u0410-\u044f]+\s+[\u0410-\u044f]+<\/h1>/)
            return matcher.group(1)
        } catch (IllegalStateException ignore) {
            return "0"
        }
    }

    protected String parseId() {
        Matcher matcher = match(/data-id-season="(\d+)"/)
        return matcher.group(1)
    }

    protected String parseSerial() {
        Matcher matcher = match(/data-id-serial="(\d+)"/)
        return matcher.group(1)
    }

    protected String parseSecure() {
        Matcher matcher = match(/(?s)data4play.*?'secureMark':\s+'([a-f0-9]+)'/)
        return matcher.group(1)
    }

    protected String parseTime() {
        Matcher matcher = match(/(?s)data4play.*?'time':\s+([0-9]+)/)
        return matcher.group(1)
    }

    protected List<String> parseSeasons() {
        Matcher matcher = match(/(?s)<h2>\s*<a\s+href="(\\/serial-\d+-[^.]+?\.html)"/)
        return matcher.iterator().collect { matcher.group(1) }.unique()
    }

    String getType() {
        return type
    }

    String getTitle() {
        return title
    }

    String getSeasonId() {
        return seasonId
    }

    String getId() {
        return id
    }

    String getSerial() {
        return serial
    }

    String getSecure() {
        return secure
    }

    String getTime() {
        return time
    }

    List<String> getAllSeasons() {
        if (!this.allSeasons) this.allSeasons = parseSeasons()
        return this.allSeasons
    }

    Map<String, String> asMap() {
        return [
                "type": getType(),
                "id": getId(),
                "serial": getSerial(),
                "secure": getSecure(),
                "time": getTime()
        ]
    }
}

class BlockedIndexPageParser extends IndexPageParser {
    BlockedIndexPageParser(String html) {
        super(html)
    }

    @Override
    String parseTime() {
        return currentTimeMillis()
    }

    @Override
    protected String parseSecure() {
        return "0"
    }
}

class PlayerPageParser extends PageParser {
    private final String playlist

    PlayerPageParser(String html, String userPreferredTranslation) {
        super(html)
        Map<String, String> availableTranslations = parseAvailableTranslations()
        this.playlist = availableTranslations.containsKey(userPreferredTranslation) ?
                availableTranslations.get(userPreferredTranslation) :
                parseDefaultPlaylist() // else default translation
    }

    private Map<String, String> parseAvailableTranslations() {
        Map<String, String> translationVariants = [:]
        try {
            String translationsList = match(/(?s)<ul\s+class="pgs-trans"(.*?)<\/ul>/).group(0)
            Matcher matcher = match(translationsList, /(?s)<li\s+data-click="translate[^>]*?>([^<]+)<\/li>[\s\n]*?<script>pl\[\d+]\s+=\s+"(.*?)";/)
            matcher.iterator().each {
                translationVariants[matcher.group(1).toLowerCase()] = matcher.group(2)
            }
        } catch (IllegalStateException ignore) {}
        return translationVariants
    }

    private String parseDefaultPlaylist() {
        Matcher matcher = match(/var\s+pl\s+=\s+\{'0':\s+"(.+)"};/)
        return matcher.group(1)
    }

    String getPlaylist() {
        return playlist
    }
}

/**
 * Simple stupid HTTP connector based on HttpURLConnection
 */
class HTTP {
    private final Proxy proxy
    private static final List<String> supportedEncodings = ["gzip", "deflate"]
    private final Map<String, String> headers
    private final CookieStore cookieJar = new CustomCookieStore()
    private final CookieManager cookieManager = new CookieManager(this.cookieJar, CookiePolicy.ACCEPT_ALL)

    HTTP(Map<String,String> headers = [:], Map<String, Map<String, String>> cookies = [:], Proxy proxy = Proxy.NO_PROXY) {
        this.proxy = proxy
        this.headers = headers << ["Accept-Encoding" : supportedEncodings.join(", ")]
        CookieHandler.setDefault(this.cookieManager)
        cookies.each { domain, map ->
            map.each { key, value ->
                this.cookieJar.add(new URI(domain), new HttpCookie(key, value))
            }
        }
    }

    private HttpURLConnection constructConnection(URL url) {
        HttpURLConnection connection = url.openConnection(this.proxy) as HttpURLConnection
        this.headers.each { key, value -> connection.setRequestProperty(key, value) }
        return connection
    }

    Response GET(String url) {
        return GET(new URL(url))
    }

    Response GET(URL url) {
        HttpURLConnection connection = this.constructConnection(url)
        connection.setRequestMethod("GET")
        connection.connect()
        return new Response(connection)
    }

    Response POST(String url, Map<String, String> params) {
        return POST(new URL(url), params)
    }
    Response POST(URL url, Map<String, String> params) {
        HttpURLConnection connection = this.constructConnection(url)
        connection.with {
            setRequestMethod("POST")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Content-type", "application/x-www-form-urlencoded")
            doOutput = true
        }
        String data = URLEncodedUtils.format(
                params.collect { key, value -> new BasicNameValuePair(key, value) },
                StandardCharsets.UTF_8
        )
        OutputStreamWriter writer = new OutputStreamWriter(connection.outputStream)
        writer.with {
            write(data)
            flush()
            close()
        }
        connection.connect()
        return new Response(connection)
    }

    class CustomCookieStore implements CookieStore {
        CookieStore store = new CookieManager().getCookieStore()

        void add(URI uri, HttpCookie cookie) {
            if (!cookie.domain) cookie.domain = uri.isAbsolute() ? ".$uri.host" : ".$uri.path"
            if (!cookie.path) cookie.path = uri.isAbsolute() ? uri.path : "/"
            if (cookie.version == 1) cookie.version = 0
            this.store.add(uri, cookie)
        }

        List<HttpCookie> get(URI uri) {
            return this.store.get(uri)
        }

        List<HttpCookie> getCookies() {
            return this.store.getCookies()
        }

        List<URI> getURIs() {
            return this.store.getURIs()
        }

        boolean remove(URI uri, HttpCookie cookie) {
            return this.store.remove(uri, cookie)
        }

        boolean removeAll() {
            return this.store.removeAll()
        }
    }

    class Response {
        final String text
        final int responseCode
        final URL url
        def json

        Response(HttpURLConnection connection) {
            this.text = decodeInputStream(connection)
            this.responseCode = connection.getResponseCode()
            this.url = connection.getURL()
        }

        def getJson() {
            if (!this.json) this.json = new JsonSlurper().parseText(this.text)
            return this.json
        }

        private static String decodeInputStream(HttpURLConnection connection) {
            String encoding = connection.getContentEncoding()
            switch (encoding) {
                case "gzip":
                    return IOUtils.toString(new GZIPInputStream(connection.inputStream), StandardCharsets.UTF_8)
                case "deflate":
                    return IOUtils.toString(new InflaterInputStream(connection.inputStream), StandardCharsets.UTF_8)
                case null:
                    return IOUtils.toString(connection.inputStream, StandardCharsets.UTF_8)
                default:
                    throw new UnsupportedEncodingException("The server has returned an unsupported encoding: $encoding")
            }
        }
    }
}
