/*
 * This file is part of Private Time.
 *
 * Private Time is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Popcorn Time is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Private Time. If not, see <http://www.gnu.org/licenses/>.
 */

package pct.droid.base.providers.media;

import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.LinkedTreeMap;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import pct.droid.base.PopcornApplication;
import pct.droid.base.R;
import pct.droid.base.providers.media.models.Genre;
import pct.droid.base.providers.media.models.Media;
import pct.droid.base.providers.media.models.Movie;
import pct.droid.base.providers.subs.SubsProvider;
import pct.droid.base.providers.subs.YSubsProvider;
import pct.droid.base.utils.LocaleUtils;

public class PrivateProvider extends MediaProvider {

    private static final PrivateProvider sMediaProvider = new PrivateProvider();
    public static String CURRENT_URL = "http://api.apiprivatetorrents.com/movies";

    private static final SubsProvider sSubsProvider = new YSubsProvider();
    private static Filters sFilters = new Filters();

    @Override
    protected OkHttpClient getClient() {
        OkHttpClient client = super.getClient().clone();
        // Use only HTTP 1.1 for YTS
        List<Protocol> proto = new ArrayList<>();
        proto.add(Protocol.HTTP_1_1);
        client.setProtocols(proto);
        return client;
    }

    @Override
    protected Call enqueue(Request request, com.squareup.okhttp.Callback requestCallback) {
        Context context = PopcornApplication.getAppContext();
        PackageInfo pInfo;
        String versionName = "0.0.0";
        try {
            pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            versionName = pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        request = request.newBuilder().removeHeader("User-Agent").addHeader("User-Agent", String.format("Mozilla/5.0 (Linux; U; Android %s; %s; %s Build/%s) AppleWebkit/534.30 (KHTML, like Gecko) PT/%s", Build.VERSION.RELEASE, LocaleUtils.getCurrentAsString(), Build.MODEL, Build.DISPLAY, versionName)).build();
        return super.enqueue(request, requestCallback);
    }

    @Override
    public Call getList(final ArrayList<Media> existingList, Filters filters, final Callback callback) {
        sFilters = filters;

        final ArrayList<Media> currentList;
        if (existingList == null) {
            currentList = new ArrayList<>();
        } else {
            currentList = (ArrayList<Media>) existingList.clone();
        }

        ArrayList<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("limit", "30"));

        if (filters == null) {
            filters = new Filters();
        }

        if (filters.keywords != null) {
            params.add(new NameValuePair("keywords", filters.keywords));
        }

        if (filters.genre != null) {
            params.add(new NameValuePair("genre", filters.genre));
        }

        if (filters.order == Filters.Order.ASC) {
            params.add(new NameValuePair("order_by", "asc"));
        } else {
            params.add(new NameValuePair("order_by", "desc"));
        }

        String sort;
        switch (filters.sort) {
            default:
            case POPULARITY:
                sort = "popularity";
                break;
            case YEAR:
                sort = "year";
                break;
            case DATE:
                sort = "dateadded";
                break;
            case RATING:
                sort = "rating";
                break;
        }

        params.add(new NameValuePair("sort", sort));

        if (filters.page != null) {
            params.add(new NameValuePair("page", Integer.toString(filters.page)));
        }

        Request.Builder requestBuilder = new Request.Builder();
        String query = buildQuery(params);
        requestBuilder.url(CURRENT_URL + "?" + query);
        requestBuilder.tag(MEDIA_CALL);

        return fetchList(currentList, requestBuilder, filters, callback);
    }

    /**
     * Fetch the list of movies from YTS
     *
     * @param currentList    Current shown list to be extended
     * @param requestBuilder Request to be executed
     * @param callback       Network callback
     * @return Call
     */
    private Call fetchList(final ArrayList<Media> currentList, final Request.Builder requestBuilder, final Filters filters, final Callback callback) {
        return enqueue(requestBuilder.build(), new com.squareup.okhttp.Callback() {
            @Override
            public void onFailure(Request request, IOException e) {
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseStr;
                    try {
                        responseStr = response.body().string();
                    } catch (SocketException e) {
                        onFailure(response.request(), new IOException("Socket failed"));
                        return;
                    }

                    YTSReponse result;
                    try {
                        result = mGson.fromJson(responseStr, YTSReponse.class);
                    } catch (IllegalStateException e) {
                        onFailure(response.request(), new IOException("JSON Failed"));
                        return;
                    } catch (JsonSyntaxException e) {
                        onFailure(response.request(), new IOException("JSON Failed"));
                        return;
                    }

                    if (result.status != null && result.status.equals("error")) {
                        callback.onFailure(new NetworkErrorException(result.status_message));
                    } else if(result.data != null && ((result.data.get("movies") != null && ((ArrayList<LinkedTreeMap<String, Object>>)result.data.get("movies")).size() <= 0) || Integer.parseInt((String)result.data.get("movie_count")) <= currentList.size())) {
                        callback.onFailure(new NetworkErrorException("No movies found"));
                    } else {
                        ArrayList<Media> formattedData = result.formatForPopcorn(currentList);
                        callback.onSuccess(filters, formattedData, true);
                        return;
                    }
                }
                onFailure(response.request(), new IOException("Couldn't connect to YTS"));
            }
        });
    }

    @Override
    public Call getDetail(ArrayList<Media> currentList, Integer index, Callback callback) {
        ArrayList<Media> returnList = new ArrayList<>();
        returnList.add(currentList.get(index));
        callback.onSuccess(null, returnList, true);
        return null;
    }

    private class YTSReponse {
        public String status;
        public String status_message;
        public LinkedTreeMap<String, Object> data;

        /**
         * Test if there is an item that already exists
         *
         * @param results List with items
         * @param id      Id of item to check for
         * @return Return the index of the item in the results
         */
        private int isInResults(ArrayList<Media> results, String id) {
            int i = 0;
            for (Media item : results) {
                if (item.videoId.equals(id)) return i;
                i++;
            }
            return -1;
        }

        /**
         * Format data for the application
         *
         * @param existingList List to be extended
         * @return List with items
         */
        public ArrayList<Media> formatForPopcorn(ArrayList<Media> existingList) {
            ArrayList<LinkedTreeMap<String, Object>> movies = new ArrayList<>();
            if (data != null) {
                movies = (ArrayList<LinkedTreeMap<String, Object>>) data.get("movies");
            }

            for (LinkedTreeMap<String, Object> item : movies) {
                Movie movie = new Movie(sMediaProvider, sSubsProvider);

                movie.videoId = (String) item.get("id");
                movie.imdbId = movie.videoId;

                int existingItem = isInResults(existingList, movie.videoId);
                if (existingItem == -1) {
                    movie.title = (String) item.get("title");
                    Double year = (Double) item.get("year");
                    movie.year = Integer.toString(year.intValue());
                    Double rating = (Double) item.get("rating");
                    rating = rating * 2;
                    movie.rating = rating.toString();
                    movie.genre = ((ArrayList<String>) item.get("genres")).get(0);
                    Map<String, Object> images = (Map<String, Object>) item.get("images");
                    movie.image = (String) images.get("posterBig");
                    movie.headerImage = ((ArrayList<String>)images.get("backdrops")).get(0);
                    movie.fullImage = movie.image;
                    movie.runtime = (String) item.get("runtime");
                    movie.synopsis = (String) item.get("synopsis");
                    movie.certification = "NC-17";

                    ArrayList<LinkedTreeMap<String, Object>> torrents =
                            (ArrayList<LinkedTreeMap<String, Object>>) item.get("torrents");
                    if (torrents != null) {
                        for (LinkedTreeMap<String, Object> torrentObj : torrents) {
                            String quality = (String) torrentObj.get("quality");
                            if (quality == null) continue;

                            Media.Torrent torrent = new Media.Torrent();

                            torrent.seeds = ((Double) torrentObj.get("seed")).intValue();
                            torrent.peers = ((Double) torrentObj.get("peer")).intValue();
                            torrent.hash = (String) torrentObj.get("hash");
                            try {
                                torrent.url = "magnet:?xt=urn:btih:" + torrent.hash + "&amp;dn=" + URLEncoder.encode(item.get("title").toString(), "utf-8") + "&amp;tr=http://exodus.desync.com:6969/announce&amp;tr=udp://tracker.openbittorrent.com:80/announce&amp;tr=udp://open.demonii.com:1337/announce&amp;tr=udp://exodus.desync.com:6969/announce&amp;tr=udp://tracker.yify-torrents.com/announce";
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                                torrent.url = (String) torrentObj.get("url");
                            }

                            movie.torrents.put(quality, torrent);
                        }
                    }

                    existingList.add(movie);
                }
            }
            return existingList;
        }
    }

    @Override
    public int getLoadingMessage() {
        return R.string.loading_movies;
    }

    @Override
    public List<NavInfo> getNavigation() {
        List<NavInfo> tabs = new ArrayList<>();
        tabs.add(new NavInfo(Filters.Sort.POPULARITY, Filters.Order.DESC, PopcornApplication.getAppContext().getString(R.string.popular)));
        tabs.add(new NavInfo(Filters.Sort.RATING, Filters.Order.DESC, PopcornApplication.getAppContext().getString(R.string.top_rated)));
        tabs.add(new NavInfo(Filters.Sort.DATE, Filters.Order.DESC, PopcornApplication.getAppContext().getString(R.string.release_date)));
        tabs.add(new NavInfo(Filters.Sort.YEAR, Filters.Order.DESC, PopcornApplication.getAppContext().getString(R.string.year)));
        return tabs;
    }

    @Override
    public List<Genre> getGenres() {
        List<Genre> returnList = new ArrayList<>();
        returnList.add(new Genre(null, R.string.genre_all));
        returnList.add(new Genre("2", "18+ Teens"));
        returnList.add(new Genre("3", "'Threesomes'"));
        returnList.add(new Genre("5", "69"));
        returnList.add(new Genre("6", "Action"));
        returnList.add(new Genre("7", "Affair"));
        returnList.add(new Genre("8", "All Girl"));
        returnList.add(new Genre("9", "All Sex"));
        returnList.add(new Genre("10", "Amateur"));
        returnList.add(new Genre("11", "Barebacking"));
        returnList.add(new Genre("12", "Anal"));
        returnList.add(new Genre("13", "Ass to Mouth"));
        returnList.add(new Genre("14", "Athletic Body"));
        returnList.add(new Genre("15", "Babysitter"));
        returnList.add(new Genre("16", "Ball licking"));
        returnList.add(new Genre("17", "BGG"));
        returnList.add(new Genre("18", "Big Butts"));
        returnList.add(new Genre("19", "Big Boobs"));
        returnList.add(new Genre("20", "Big Budget"));
        returnList.add(new Genre("21", "Big Cocks"));
        returnList.add(new Genre("22", "Big Dick"));
        returnList.add(new Genre("23", "Big Fake Boobs"));
        returnList.add(new Genre("24", "Bikini Babes"));
        returnList.add(new Genre("25", "Black"));
        returnList.add(new Genre("26", "Brunette"));
        returnList.add(new Genre("27", "Blonde"));
        returnList.add(new Genre("28", "Blow Job"));
        returnList.add(new Genre("29", "Blu-Ray"));
        returnList.add(new Genre("30", "Blue Eyes"));
        returnList.add(new Genre("31", "Brown Eyes"));
        returnList.add(new Genre("32", "Bubble Butt"));
        returnList.add(new Genre("33", "Caucasian"));
        returnList.add(new Genre("34", "Celebrity"));
        returnList.add(new Genre("35", "Chubby"));
        returnList.add(new Genre("36", "Classic"));
        returnList.add(new Genre("37", "College"));
        returnList.add(new Genre("38", "Comedy"));
        returnList.add(new Genre("39", "Compilation"));
        returnList.add(new Genre("40", "Costumes"));
        returnList.add(new Genre("41", "Couples"));
        returnList.add(new Genre("42", "Cream Pie"));
        returnList.add(new Genre("43", "Cumshots"));
        returnList.add(new Genre("44", "Curvy Woman"));
        returnList.add(new Genre("45", "Deep Throat"));
        returnList.add(new Genre("46", "Domination"));
        returnList.add(new Genre("47", "Double Penetration"));
        returnList.add(new Genre("48", "Vignette"));
        returnList.add(new Genre("49", "European"));
        returnList.add(new Genre("50", "Facial"));
        returnList.add(new Genre("51", "Fake Boobs"));
        returnList.add(new Genre("52", "Family Roleplay"));
        returnList.add(new Genre("53", "Feature"));
        returnList.add(new Genre("54", "Fetish"));
        returnList.add(new Genre("55", "First Anal"));
        returnList.add(new Genre("56", "First Double Penetration"));
        returnList.add(new Genre("57", "First Interracial"));
        returnList.add(new Genre("58", "Fishnet"));
        returnList.add(new Genre("59", "Foot"));
        returnList.add(new Genre("60", "Foreign"));
        returnList.add(new Genre("61", "Gangbang"));
        returnList.add(new Genre("62", "Gaping"));
        returnList.add(new Genre("63", "Gonzo"));
        returnList.add(new Genre("64", "Green Eyes"));
        returnList.add(new Genre("65", "Group Sex"));
        returnList.add(new Genre("66", "Hairy"));
        returnList.add(new Genre("67", "Halloween"));
        returnList.add(new Genre("68", "Hand Job"));
        returnList.add(new Genre("69", "Hazel Eyes"));
        returnList.add(new Genre("70", "High Definition"));
        returnList.add(new Genre("71", "High Heels"));
        returnList.add(new Genre("72", "Home Made Movies"));
        returnList.add(new Genre("73", "Indian"));
        returnList.add(new Genre("74", "Innie Pussy"));
        returnList.add(new Genre("75", "Interactive Sex"));
        returnList.add(new Genre("76", "Interracial"));
        returnList.add(new Genre("77", "Latin"));
        returnList.add(new Genre("78", "Lingerie"));
        returnList.add(new Genre("79", "Made For Women"));
        returnList.add(new Genre("80", "Massage"));
        returnList.add(new Genre("81", "Masturbation"));
        returnList.add(new Genre("82", "Mature"));
        returnList.add(new Genre("83", "Medium Ass"));
        returnList.add(new Genre("84", "Natural Tits"));
        returnList.add(new Genre("85", "Medium Tits"));
        returnList.add(new Genre("86", "MILF"));
        returnList.add(new Genre("87", "Military"));
        returnList.add(new Genre("88", "Mystery"));
        returnList.add(new Genre("89", "Nurses & Doctors"));
        returnList.add(new Genre("90", "Office"));
        returnList.add(new Genre("91", "Oiled"));
        returnList.add(new Genre("92", "Older Men"));
        returnList.add(new Genre("93", "Oral"));
        returnList.add(new Genre("94", "Orgy"));
        returnList.add(new Genre("95", "Outie Pussy"));
        returnList.add(new Genre("96", "P.O.V."));
        returnList.add(new Genre("97", "Stockings & Pantyhose"));
        returnList.add(new Genre("98", "Parody"));
        returnList.add(new Genre("99", "Petite"));
        returnList.add(new Genre("100", "Piercings"));
        returnList.add(new Genre("101", "Prison Chicks"));
        returnList.add(new Genre("102", "Redhead"));
        returnList.add(new Genre("103", "Romance"));
        returnList.add(new Genre("104", "Science Fiction"));
        returnList.add(new Genre("105", "Shaved"));
        returnList.add(new Genre("106", "Small Boobs"));
        returnList.add(new Genre("107", "Secret Agents"));
        returnList.add(new Genre("108", "Squirting"));
        returnList.add(new Genre("109", "Star showcase"));
        returnList.add(new Genre("110", "Strap-Ons"));
        returnList.add(new Genre("111", "Summer"));
        returnList.add(new Genre("112", "Tattoos"));
        returnList.add(new Genre("113", "Teachers"));
        returnList.add(new Genre("114", "Titty Fucking"));
        returnList.add(new Genre("115", "Transsexual"));
        returnList.add(new Genre("116", "Voluptuous"));
        returnList.add(new Genre("117", "Voyeurism"));
        returnList.add(new Genre("118", "Wives"));
        return returnList;
    }

}
