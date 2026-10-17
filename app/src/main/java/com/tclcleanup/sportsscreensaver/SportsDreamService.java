package com.tclcleanup.sportsscreensaver;

import android.animation.ValueAnimator;
import android.service.dreams.DreamService;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SportsDreamService extends DreamService {
    private static final String TAG = "SportsScreensaver";
    // Change this value to configure how long the dashboard remains visible.
    private static final long AMBIENT_TIMEOUT_SECONDS = 300L;
    private SportsView view;
    private IdleTimer idleTimer;
    private String weatherText = "Weather unavailable";
    private String weatherTemp = "--°F";
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler();

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SportsView();
        setContentView(view);
        view.requestFocus();
        idleTimer = new IdleTimer(handler, AMBIENT_TIMEOUT_SECONDS * 1000L,
                () -> { if (view != null) view.setAmbientMode(true); });
        idleTimer.start();
        refresh();
        refreshWeather();
    }

    private void refreshWeather() {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=40.7879&longitude=-74.0143&current=temperature_2m,weather_code&temperature_unit=fahrenheit&timezone=America%2FNew_York");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                if (connection.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder body = new StringBuilder(); String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                    JSONObject current = new JSONObject(body.toString()).optJSONObject("current");
                    if (current != null) {
                        double temp = current.optDouble("temperature_2m", Double.NaN);
                        int code = current.optInt("weather_code", -1);
                        String description = weatherDescription(code);
                        handler.post(() -> { weatherTemp = Double.isNaN(temp) ? "--°F" : Math.round(temp) + "°F"; weatherText = description; if (view != null) view.invalidate(); });
                    }
                }
            } catch (Exception e) { Log.w(TAG, "Weather fetch failed", e); }
            finally { if (connection != null) connection.disconnect(); }
        });
        handler.postDelayed(this::refreshWeather, 30 * 60 * 1000L);
    }

    private String weatherDescription(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Foggy";
        if (code >= 51 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorms";
        return "Cloudy";
    }

    private void refresh() {
        List<Game> games = Collections.synchronizedList(new ArrayList<>());
        String[][] leagues = {{"football", "nfl", "NFL"}, {"basketball", "nba", "NBA"}, {"basketball", "wnba", "WNBA"}};
        for (String[] league : leagues) executor.execute(() -> {
            Calendar day = Calendar.getInstance();
            for (int i = 0; i < 8; i++) {
                String date = String.format(Locale.US, "%04d%02d%02d", day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
                fetch(league[0], league[1], league[2], date, games);
                day.add(Calendar.DAY_OF_YEAR, 1);
            }
            synchronized (games) {
                Collections.sort(games, Comparator.comparingLong(g -> g.when));
                List<Game> result = games.size() > 8 ? new ArrayList<>(games.subList(0, 8)) : new ArrayList<>(games);
                Log.i(TAG, "Loaded " + result.size() + " upcoming games so far");
                handler.post(() -> { if (view != null) view.setGames(result); });
            }
        });
        handler.postDelayed(this::refresh, 30 * 60 * 1000L);
    }

    private void fetch(String sport, String league, String label, String date, List<Game> out) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://site.api.espn.com/apis/site/v2/sports/" + sport + "/" + league + "/scoreboard?dates=" + date + "&limit=100");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "SportsScreensaver/0.1");
            int response = connection.getResponseCode();
            if (response != 200) { Log.w(TAG, "ESPN " + league + " HTTP " + response); return; }
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder body = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) body.append(line);
            JSONArray events = new JSONObject(body.toString()).optJSONArray("events");
            Log.i(TAG, league + " " + date + " returned " + (events == null ? -1 : events.length()) + " events");
            if (events == null) return;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i); if (event == null) continue;
                long when;
                String dateText = event.optString("date");
                try {
                    if (dateText.matches(".*T\\d{2}:\\d{2}Z$")) dateText = dateText.replaceAll("(T\\d{2}:\\d{2})Z$", "$1:00Z");
                    when = Instant.parse(dateText).toEpochMilli();
                } catch (Exception e) { Log.w(TAG, "Could not parse date for " + league + ": " + dateText); continue; }
                if (when < System.currentTimeMillis() - 30 * 60 * 1000L) continue;
                JSONObject competition = event.optJSONArray("competitions") == null ? null : event.optJSONArray("competitions").optJSONObject(0);
                if (competition == null) continue;
                JSONArray teams = competition.optJSONArray("competitors");
                String home = "Home", away = "Away", homeCode = "", awayCode = "";
                if (teams != null) for (int j = 0; j < teams.length(); j++) {
                    JSONObject team = teams.optJSONObject(j); if (team == null) continue;
                    JSONObject teamInfo = team.optJSONObject("team");
                    String name = teamInfo == null ? "" : teamInfo.optString("displayName", "");
                    String code = teamInfo == null ? "" : teamInfo.optString("abbreviation", "");
                    String logo = teamInfo == null ? "" : teamInfo.optString("logo", "");
                    JSONArray logos = teamInfo == null ? null : teamInfo.optJSONArray("logos");
                    if (logo.isEmpty() && logos != null && logos.length() > 0) logo = logos.optJSONObject(0).optString("href", "");
                    if ("home".equals(team.optString("homeAway"))) { home = name; homeCode = code; } else { away = name; awayCode = code; }
                }
                String network = "Schedule / provider";
                JSONArray broadcasts = competition.optJSONArray("broadcasts");
                if (broadcasts != null && broadcasts.length() > 0) {
                    JSONObject broadcast = broadcasts.optJSONObject(0);
                    JSONArray names = broadcast == null ? null : broadcast.optJSONArray("names");
                    if (names != null && names.length() > 0) network = names.optString(0, network);
                    else if (broadcast != null) network = broadcast.optString("name", network);
                }
                if (network.equals("Schedule / provider")) {
                    JSONArray geo = competition.optJSONArray("geoBroadcasts");
                    if (geo != null && geo.length() > 0) network = geo.optJSONObject(0).optString("media", network);
                }
                String link = event.optString("links", "");
                String awayLogo = "", homeLogo = "";
                if (teams != null) for (int j = 0; j < teams.length(); j++) {
                    JSONObject team = teams.optJSONObject(j); JSONObject teamInfo = team == null ? null : team.optJSONObject("team");
                    JSONArray logos = teamInfo == null ? null : teamInfo.optJSONArray("logos");
                    String logo = teamInfo == null ? "" : teamInfo.optString("logo", "");
                    if (logo.isEmpty() && logos != null && logos.length() > 0) logo = logos.optJSONObject(0).optString("href", "");
                    if (team != null && "home".equals(team.optString("homeAway"))) homeLogo = logo; else awayLogo = logo;
                }
                out.add(new Game(label, away, home, awayCode, homeCode, awayLogo, homeLogo, network, when, "https://www.espn.com/" + league + "/game?event=" + event.optString("id")));
                Log.i(TAG, "Added " + label + " " + away + " at " + home);
            }
        } catch (Exception e) { Log.e(TAG, "ESPN " + league + " fetch failed", e); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private void noteInteraction() {
        if (idleTimer != null) idleTimer.reset();
        if (view != null) view.setAmbientMode(false);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) noteInteraction();
        return true;
    }

    @Override public void onDetachedFromWindow() {
        if (idleTimer != null) idleTimer.stop();
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private final class SportsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Game> games = new ArrayList<>();
        private final Typeface regular = loadInter(400);
        private final Typeface medium = loadInter(500);
        private final Typeface semibold = loadInter(600);
        private final Typeface light = loadInter(300);
        private final Typeface ambientLight = light;
        private final Typeface ambientRegular = regular;
        private final ZoneId easternTime = ZoneId.of("America/New_York");
        private final DateTimeFormatter headerDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US).withZone(easternTime);
        private final DateTimeFormatter headerTime = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(easternTime);
        private final DateTimeFormatter gameTime = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy  ·  HH:mm", Locale.US).withZone(easternTime);
        private final DateTimeFormatter ambientDate = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US).withZone(ZoneId.of("America/New_York"));
        private final DateTimeFormatter ambientTime = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.of("America/New_York"));
        private boolean ambientMode;
        private boolean transitionRunning;
        private long transitionStarted;
        private long shiftMinute = Long.MIN_VALUE;
        private float shiftX, shiftY;

        SportsView() {
            super(SportsDreamService.this);
            // Some Android TV compositors do not preserve the undamaged portion of a
            // hardware canvas between asynchronous logo redraws.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        private Typeface loadInter(int weight) {
            try {
                return new Typeface.Builder(getAssets(), "fonts/inter.ttf").setWeight(weight).build();
            } catch (Exception e) {
                return Typeface.create("sans", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            }
        }

        void setAmbientMode(boolean enabled) {
            if (ambientMode == enabled) return;
            ambientMode = enabled;
            transitionRunning = ValueAnimator.areAnimatorsEnabled();
            transitionStarted = SystemClock.uptimeMillis();
            invalidate();
        }

        void setGames(List<Game> incoming) {
            games.clear();
            games.addAll(incoming);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            final float w = getWidth(), h = getHeight();
            final float s = Math.min(w / 1920f, h / 1080f);
            drawBackground(c, w, h);

            float progress = 1f;
            if (transitionRunning) {
                progress = Math.min(1f, (SystemClock.uptimeMillis() - transitionStarted) / 800f);
                if (progress < 1f) postInvalidateDelayed(16L);
                else transitionRunning = false;
            }
            float ambientAlpha = ambientMode ? progress : 1f - progress;
            float dashboardAlpha = 1f - ambientAlpha;

            if (dashboardAlpha > 0f) {
                int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * dashboardAlpha));
                drawDashboard(c, w, h, s);
                c.restoreToCount(layer);
            }
            if (ambientAlpha > 0f) {
                int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * ambientAlpha));
                drawAmbientClock(c, w, h);
                c.restoreToCount(layer);
            }

            if (!transitionRunning) {
                if (ambientMode) {
                    long delay = 1000L - (System.currentTimeMillis() % 1000L);
                    postInvalidateDelayed(delay);
                } else {
                    postInvalidateDelayed(30_000L);
                }
            }
        }

        private void drawDashboard(Canvas c, float w, float h, float s) {
            float ox = (w - 1920 * s) / 2f;
            float oy = (h - 1080 * s) / 2f;
            Instant now = Instant.now();

            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
            p.setFontFeatureSettings("tnum");
            p.setTypeface(light);
            p.setColor(Color.rgb(200, 184, 181));
            p.setTextSize(44 * s);
            c.drawText(headerDate.format(now), ox + 96 * s, oy + 104 * s, p);

            p.setTypeface(regular);
            p.setColor(Color.rgb(248, 246, 243));
            p.setTextSize(80 * s);
            c.drawText(headerTime.format(now), ox + 96 * s, oy + 190 * s, p);
            p.setFontFeatureSettings(null);
            drawWeather(c, ox, oy, s);

            float gridX = ox + 96 * s;
            float gridY = oy + 260 * s;
            float gap = 24 * s;
            float cardW = 560 * s;
            float cardH = 300 * s;
            int count = Math.min(games.size(), 6);
            for (int i = 0; i < count; i++) {
                int column = i % 3;
                int row = i / 3;
                drawGame(c, games.get(i), i, gridX + column * (cardW + gap),
                        gridY + row * (cardH + gap), cardW, cardH, s);
            }

            p.setTypeface(regular);
            p.setColor(Color.rgb(200, 184, 181));
            p.setTextSize(16 * s);
            c.drawText("ESPN schedule  ·  Updates every 30 minutes", ox + 96 * s, oy + 1026 * s, p);
        }

        private void drawAmbientClock(Canvas c, float w, float h) {
            float scale = h / 1080f;
            long minute = System.currentTimeMillis() / 60_000L;
            if (minute != shiftMinute) {
                shiftMinute = minute;
                int[] offsets = { -5, -3, -2, 2, 3, 5 };
                shiftX = offsets[(int) Math.floorMod(minute, offsets.length)] * scale;
                shiftY = offsets[(int) Math.floorMod(minute / offsets.length, offsets.length)] * scale;
            }

            Instant now = Instant.now();
            String date = ambientDate.format(now);
            String time = ambientTime.format(now);
            float centerX = w / 2f + shiftX;
            float centerY = h / 2f + shiftY;

            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(ambientLight);
            p.setColor(Color.rgb(200, 184, 181));
            p.setTextSize(36 * scale);
            p.setFontFeatureSettings("tnum");
            c.drawText(date, centerX, centerY - 58 * scale, p);

            p.setTypeface(ambientRegular);
            p.setColor(Color.rgb(248, 246, 243));
            p.setTextSize(110 * scale);
            p.setFontFeatureSettings("tnum");
            c.drawText(time, centerX, centerY + 76 * scale, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setFontFeatureSettings(null);
        }

        private void drawBackground(Canvas c, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(Color.rgb(33, 3, 3));
            c.drawRect(0, 0, w, h, p);

            p.setShader(new RadialGradient(w * .75f, 0, w * .62f,
                    new int[] { Color.argb(191, 220, 55, 10), Color.argb(70, 143, 20, 11), Color.TRANSPARENT },
                    new float[] { 0f, .48f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w, h, w * .46f,
                    new int[] { Color.argb(217, 255, 149, 0), Color.argb(55, 217, 68, 12), Color.TRANSPARENT },
                    new float[] { 0f, .46f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(0, h * .85f, w * .38f,
                    new int[] { Color.argb(140, 180, 55, 5), Color.TRANSPARENT }, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        private void drawWeather(Canvas c, float ox, float oy, float s) {
            float cx = ox + 1570 * s, cy = oy + 120 * s;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(3 * s);
            p.setColor(Color.rgb(255, 182, 39));
            c.drawCircle(cx + 5 * s, cy - 8 * s, 14 * s, p);
            c.drawCircle(cx - 9 * s, cy, 11 * s, p);
            c.drawCircle(cx + 18 * s, cy + 2 * s, 9 * s, p);
            c.drawLine(cx - 20 * s, cy + 10 * s, cx + 26 * s, cy + 10 * s, p);
            c.drawLine(cx + 5 * s, cy - 29 * s, cx + 5 * s, cy - 34 * s, p);
            c.drawLine(cx - 9 * s, cy - 25 * s, cx - 12 * s, cy - 29 * s, p);
            c.drawLine(cx + 19 * s, cy - 25 * s, cx + 22 * s, cy - 29 * s, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);

            p.setTypeface(regular);
            p.setColor(Color.rgb(248, 246, 243));
            p.setTextSize(20 * s);
            c.drawText("West New York, NJ", ox + 1635 * s, oy + 116 * s, p);
            p.setColor(Color.rgb(200, 184, 181));
            p.setTextSize(18 * s);
            c.drawText(weatherTemp + "  ·  " + weatherText, ox + 1635 * s, oy + 150 * s, p);
        }

        private void drawGame(Canvas c, Game g, int index, float x, float y,
                              float cardW, float cardH, float s) {
            RectF card = new RectF(x, y, x + cardW, y + cardH);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(148, 45, 8, 8));
            c.drawRoundRect(card, 16 * s, 16 * s, p);

            int[] borders = {
                    Color.rgb(213, 43, 23), Color.rgb(229, 107, 10), Color.rgb(245, 158, 11),
                    Color.rgb(229, 107, 10), Color.rgb(213, 43, 23), Color.rgb(245, 158, 11)
            };
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1 * s);
            p.setColor(borders[index % borders.length]);
            c.drawRoundRect(card, 16 * s, 16 * s, p);
            p.setStyle(Paint.Style.FILL);

            float textX = x + 40 * s;
            p.setTypeface(medium);
            p.setColor(Color.WHITE);
            p.setTextSize(21 * s);
            c.drawText(g.league, textX, y + 56 * s, p);

            p.setTypeface(semibold);
            p.setColor(Color.rgb(248, 246, 243));
            p.setTextSize(28 * s);
            c.drawText(g.away, textX, y + 119 * s, p);

            p.setTypeface(medium);
            p.setColor(Color.rgb(169, 150, 148));
            p.setTextSize(22 * s);
            c.drawText("vs", textX, y + 157 * s, p);

            p.setTypeface(semibold);
            p.setColor(Color.rgb(248, 246, 243));
            p.setTextSize(28 * s);
            c.save();
            c.clipRect(textX, y, x + cardW - 40 * s, y + cardH);
            c.drawText(g.home, textX, y + 201 * s, p);
            c.restore();

            p.setTypeface(regular);
            p.setColor(Color.rgb(255, 170, 64));
            p.setTextSize(21 * s);
            p.setFontFeatureSettings("tnum");
            c.drawText(gameTime.format(Instant.ofEpochMilli(g.when)), textX, y + cardH - 37 * s, p);
            p.setFontFeatureSettings(null);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            noteInteraction();
            return true;
        }

        @Override public boolean onGenericMotionEvent(MotionEvent event) {
            noteInteraction();
            return true;
        }

        @Override public boolean onHoverEvent(MotionEvent event) {
            noteInteraction();
            return true;
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            noteInteraction();
            return true;
        }
    }
    private static final class Game { final String league, away, home, awayCode, homeCode, awayLogo, homeLogo, network, url; final long when; Game(String l, String a, String h, String ac, String hc, String al, String hl, String n, long t, String u) { league=l; away=a; home=h; awayCode=ac; homeCode=hc; awayLogo=al; homeLogo=hl; network=n; when=t; url=u; } }
}
