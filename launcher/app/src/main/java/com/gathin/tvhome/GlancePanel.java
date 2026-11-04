package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather and headlines on the cinematic shelf: two cards of one fixed size between the top bar and the dock,
 * their outer edges on the dock's edges and the gap between them on the screen's centre line. News on the left
 * (three stories a page, Up/Down picks one while the card is focused, OK opens it), weather on the right (now, six
 * hours, seven days) on a background tinted by the sky. Content changes never resize anything.
 * The launcher has no network access: the data comes from the TV Remote app's GlanceProvider, read when Home
 * resumes and when the provider says it changed. Nothing polls; one timer turns the news page while Home is shown.
 */
final class GlancePanel {
    private static final Uri URI = Uri.parse("content://home.tvremote.glance/glance");
    private static final long PAGE_MS = 30_000, BREAKING_MS = 20 * 60_000L;
    private static final int STORIES = 3;
    private static final int SIDE_DP = 58, GAP_DP = 16, CARD_H_DP = 178, SLOT_H_DP = 54, RADIUS_DP = 14;
    private static final int WHITE = 0xFFFFFFFF, BRIGHT = 0xF2FFFFFF, SOFT = 0xD9FFFFFF, DIM = 0x8CFFFFFF;
    private static final int[] NEWS_TINT = {0x2EFFFFFF, 0x14FFFFFF};

    private final Activity act;
    private final View topBar;
    private final Runnable onCardFocused;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Typeface light, regular, medium;
    private final LinearLayout weather, newsCard, stories, hours, days;
    private final ImageView nowIcon;
    private final TextView nowTemp, nowTitle, nowSub;
    private final LinearLayout[] slot = new LinearLayout[STORIES];
    private final ImageView[] slotIcon = new ImageView[STORIES];
    private final TextView[] slotSource = new TextView[STORIES], slotAge = new TextView[STORIES];
    private final TextView[] slotBadge = new TextView[STORIES], slotTitle = new TextView[STORIES];
    private final String[] slotUrl = new String[STORIES];
    private final ContentObserver observer;
    private final Runnable turnPage = this::nextPage;
    private final Map<String, Bitmap> icons = new HashMap<>();
    private JSONArray items;
    private String weatherUrl;
    private int page, selected, filled, tintCode = -1;
    private boolean tintDay;
    private boolean resumed, shown, hasWeather;

    GlancePanel(Activity a, View hero, Runnable onCardFocused) {
        act = a;
        this.onCardFocused = onCardFocused;
        topBar = a.findViewById(R.id.topButtons);
        d = a.getResources().getDisplayMetrics().density;
        light = a.getResources().getFont(R.font.outfit_light);
        regular = a.getResources().getFont(R.font.outfit_regular);
        medium = a.getResources().getFont(R.font.outfit_medium);
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                load();
            }
        };

        // ---- weather card: now, six hours, a rule, seven days
        weather = card(NEWS_TINT);
        weather.setPadding(px(16), px(10), px(16), px(10));
        weather.setGravity(Gravity.CENTER_VERTICAL);   // the fixed height leaves a little air: share it
        LinearLayout now = row(Gravity.CENTER_VERTICAL);
        nowIcon = icon(26);
        now.addView(nowIcon);
        nowTemp = text(24, WHITE, light);
        nowTemp.setPadding(px(8), 0, px(10), 0);
        now.addView(nowTemp);
        LinearLayout nowText = new LinearLayout(a);
        nowText.setOrientation(LinearLayout.VERTICAL);
        nowTitle = text(12, BRIGHT, medium);
        nowSub = text(10, DIM, regular);
        nowText.addView(nowTitle);
        nowText.addView(nowSub, margins(0, 2, 0, 0));
        now.addView(nowText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        weather.addView(now);
        hours = row(Gravity.CENTER);
        weather.addView(hours, sized(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        View rule = new View(a);
        rule.setBackgroundColor(0x24FFFFFF);
        weather.addView(rule, sized(ViewGroup.LayoutParams.MATCH_PARENT, 1, 0, 5, 0, 5));
        days = row(Gravity.CENTER);
        weather.addView(days, sized(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        weather.setOnClickListener(v -> open(weatherUrl));
        weather.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return topBar.requestFocus();
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;   // the screen's edge: stay
        });

        // ---- news card: three fixed story slots
        newsCard = card(NEWS_TINT);
        newsCard.setPadding(px(8), px(8), px(8), px(8));
        stories = new LinearLayout(a);
        stories.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < STORIES; i++) {
            LinearLayout s = new LinearLayout(a);
            s.setOrientation(LinearLayout.VERTICAL);
            s.setPadding(px(8), px(3), px(8), px(3));
            LinearLayout meta = row(Gravity.CENTER_VERTICAL);
            ImageView ic = icon(14);
            ic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 3 * d);
                }
            });
            ic.setClipToOutline(true);
            meta.addView(ic, sized(px(14), px(14), 0, 0, 6, 0));   // fixed: feeds send 16 to 64 px icons
            TextView src = text(10, BRIGHT, medium);
            meta.addView(src);
            TextView age = text(10, DIM, regular);
            meta.addView(age, margins(6, 0, 0, 0));
            meta.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            TextView badge = text(8, WHITE, medium);
            badge.setText("BREAKING");
            badge.setLetterSpacing(0.1f);
            badge.setBackgroundResource(R.drawable.glance_badge);
            badge.setPadding(px(5), px(1), px(5), px(1));
            meta.addView(badge);
            s.addView(meta);
            TextView title = text(13, WHITE, regular);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(1 * d, 1f);
            s.addView(title, sized(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
            slot[i] = s;
            slotIcon[i] = ic;
            slotSource[i] = src;
            slotAge[i] = age;
            slotBadge[i] = badge;
            slotTitle[i] = title;
            stories.addView(s, sized(ViewGroup.LayoutParams.MATCH_PARENT, px(SLOT_H_DP), 0, 0, 0, 0));
        }
        newsCard.addView(stories);
        newsCard.setOnClickListener(v -> open(slotUrl[selected]));
        newsCard.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (selected > 0) select(selected - 1);
                    else topBar.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (selected < filled - 1) {
                        select(selected + 1);
                        return true;
                    }
                    return false;   // down to the dock
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;    // the screen's edge: stay
                default:
                    return false;
            }
        });
        newsCard.setOnFocusChangeListener((v, focused) -> {
            if (focused) onCardFocused.run();
            highlight(focused);
        });

        // Fixed sizes from the screen width: two equal cards, a centred gap, outer edges on the dock's edges.
        int screenDp = Math.round(a.getResources().getDisplayMetrics().widthPixels / d);
        int cardW = px((screenDp - 2 * SIDE_DP - GAP_DP) / 2);
        ViewGroup parent = (ViewGroup) hero.getParent();
        int at = parent.indexOfChild(hero) + 1;
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(cardW, px(CARD_H_DP), Gravity.TOP | Gravity.START);
        nlp.leftMargin = px(SIDE_DP);
        parent.addView(newsCard, at, nlp);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(cardW, px(CARD_H_DP), Gravity.TOP | Gravity.END);
        wlp.rightMargin = px(SIDE_DP);
        parent.addView(weather, at + 1, wlp);
        for (View v : new View[]{weather, newsCard}) {
            v.setAlpha(0f);
            v.setVisibility(View.INVISIBLE);
            // Drawn once into a GPU layer and only redrawn when their content changes: the shelf's glide and
            // glow animate every frame, and re-drawing ~60 text and icon views each time cost this GPU its 60 fps.
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
    }

    /** The shelf is showing: both cards sit under the top bar. */
    void show() {
        shown = true;
        Runnable place = () -> {
            int[] bar = new int[2], root = new int[2];
            topBar.getLocationInWindow(bar);
            ((View) weather.getParent()).getLocationInWindow(root);
            float y = bar[1] - root[1] + topBar.getHeight() + 16 * d;
            weather.setY(y);
            newsCard.setY(y);
        };
        if (topBar.getHeight() == 0) topBar.post(place);
        else place.run();
        fade(weather, hasWeather);
        fade(newsCard, items != null);
    }

    /** Focus left the dock for the grid (or Home was rebuilt). A focused card keeps the cards up. */
    void hide() {
        if (weather.hasFocus() || newsCard.hasFocus()) return;
        shown = false;
        fade(weather, false);
        fade(newsCard, false);
    }

    /** Up from the dock: the news card above its left half (its bottom story), the weather card above the right. */
    boolean focusCard(boolean left) {
        if (left && newsCard.getVisibility() == View.VISIBLE) {
            selected = Math.max(0, filled - 1);
            return newsCard.requestFocus();
        }
        View target = weather.getVisibility() == View.VISIBLE ? weather
                : newsCard.getVisibility() == View.VISIBLE ? newsCard : null;
        return target != null && target.requestFocus();
    }

    void onResume() {
        resumed = true;
        act.getContentResolver().registerContentObserver(URI, false, observer);
        load();
        main.removeCallbacks(turnPage);
        main.postDelayed(turnPage, PAGE_MS);
    }

    void onPause() {
        resumed = false;
        act.getContentResolver().unregisterContentObserver(observer);
        main.removeCallbacks(turnPage);
    }

    void destroy() {
        onPause();
        io.shutdownNow();
    }

    private void fade(View v, boolean on) {
        v.animate().cancel();
        if (on && shown) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(220).start();
        } else {
            v.animate().alpha(0f).setDuration(140).withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
        }
    }

    private void open(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(act, "Install a browser to open this", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- data

    private void load() {
        io.execute(() -> {
            String w = null, n = null;
            try (Cursor c = act.getContentResolver().query(URI, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if ("weather".equals(c.getString(0))) w = c.getString(1);
                    else if ("news".equals(c.getString(0))) n = c.getString(1);
                }
            } catch (RuntimeException e) {
                // TV Remote is not installed or not ready: no cards, nothing else changes
            }
            String weatherJson = w, newsJson = n;
            main.post(() -> bind(weatherJson, newsJson));
        });
    }

    private void bind(String weatherJson, String newsJson) {
        try {
            if (weatherJson != null) {
                bindWeather(new JSONObject(weatherJson));
                hasWeather = true;
            }
        } catch (JSONException e) {
            hasWeather = false;
        }
        try {
            JSONArray list = newsJson == null ? null : new JSONObject(newsJson).getJSONArray("items");
            items = list != null && list.length() > 0 ? list : null;
        } catch (JSONException e) {
            items = null;
        }
        if (!newsCard.hasFocus()) showPage();
        if (shown) {
            fade(weather, hasWeather);
            fade(newsCard, items != null);
        }
    }

    private void bindWeather(JSONObject w) throws JSONException {
        weatherUrl = w.optString("url");
        int code = w.getInt("c");
        boolean day = w.optBoolean("d", true);
        if (code != tintCode || day != tintDay) {
            tintCode = code;
            tintDay = day;
            weather.setBackground(background(sky(code, day)));
        }
        nowIcon.setImageResource(icon(code, day));
        nowTemp.setText(w.getInt("t") + "°");
        nowTitle.setText(w.optString("label") + " · " + w.optString("city"));
        String sub = "H " + w.getInt("hi") + "°  L " + w.getInt("lo") + "°";
        if (!w.optString("note").isEmpty()) sub += "  ·  " + w.optString("note");
        nowSub.setText(sub);
        hours.removeAllViews();
        JSONArray h = w.getJSONArray("hours");
        for (int i = 0; i < h.length(); i++) {
            JSONObject o = h.getJSONObject(i);
            hours.addView(cell(o.getString("h"), icon(o.getInt("c"), o.optBoolean("d", true)), o.getInt("t") + "°"),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        days.removeAllViews();
        JSONArray dd = w.getJSONArray("days");
        for (int i = 0; i < dd.length(); i++) {
            JSONObject o = dd.getJSONObject(i);
            String hi = o.getInt("hi") + "°", lo = o.getInt("lo") + "°";
            SpannableString range = new SpannableString(hi + " " + lo);
            range.setSpan(new ForegroundColorSpan(DIM), hi.length() + 1, range.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            days.addView(cell(o.getString("n"), icon(o.getInt("c"), true), range),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void nextPage() {
        if (!resumed) return;
        main.postDelayed(turnPage, PAGE_MS);
        // A focused card keeps its page, so OK opens the story being read.
        if (items == null || items.length() <= STORIES || newsCard.hasFocus()) return;
        page++;
        if (!shown) {
            showPage();
            return;
        }
        stories.animate().cancel();
        stories.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            showPage();
            stories.animate().alpha(1f).setDuration(300).start();
        }).start();
    }

    /** Fills the three slots from the current page; an empty slot keeps its place, so the card never moves. */
    private void showPage() {
        if (items == null) return;
        int pages = (items.length() + STORIES - 1) / STORIES;
        int first = (page % pages) * STORIES;
        filled = 0;
        for (int i = 0; i < STORIES; i++) {
            JSONObject it = items.optJSONObject(first + i);
            if (it == null) {
                slot[i].setVisibility(View.INVISIBLE);
                slotUrl[i] = null;
                continue;
            }
            filled = i + 1;
            slot[i].setVisibility(View.VISIBLE);
            long ts = it.optLong("ts"), since = System.currentTimeMillis() - ts;
            slotBadge[i].setVisibility(ts > 0 && since >= 0 && since < BREAKING_MS ? View.VISIBLE : View.GONE);
            slotTitle[i].setText(it.optString("t"));
            slotSource[i].setText(it.optString("s"));
            slotAge[i].setText(ts > 0 ? "· " + ago(since) : "");
            Bitmap b = favicon(it.optString("i"));
            slotIcon[i].setImageBitmap(b);
            slotIcon[i].setVisibility(b == null ? View.GONE : View.VISIBLE);
            slotUrl[i] = it.optString("u");
        }
        selected = Math.min(selected, Math.max(0, filled - 1));
        highlight(newsCard.hasFocus());
    }

    private void select(int i) {
        selected = i;
        highlight(true);
    }

    /** The picked story gets a soft lit band while the card has focus; none otherwise. */
    private void highlight(boolean on) {
        for (int i = 0; i < STORIES; i++) {
            if (on && i == selected) {
                GradientDrawable band = new GradientDrawable();
                band.setColor(0x29FFFFFF);
                band.setCornerRadius(9 * d);
                slot[i].setBackground(band);
            } else {
                slot[i].setBackground(null);
            }
        }
    }

    private Bitmap favicon(String base64) {
        if (base64.isEmpty()) return null;
        if (icons.containsKey(base64)) return icons.get(base64);
        Bitmap b = null;
        try {
            byte[] png = Base64.decode(base64, Base64.DEFAULT);
            b = BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (IllegalArgumentException ignored) {
            // a broken icon leaves the source name alone
        }
        icons.put(base64, b);
        return b;
    }

    static String ago(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 1) return "now";
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return (min / 60) + " h ago";
        return (min / (24 * 60)) + " d ago";
    }

    /** WMO weather code to icon; clear and partly cloudy follow day and night. */
    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code <= 2) return day ? R.drawable.wx_sun_cloud : R.drawable.wx_moon_cloud;
        if (code == 3) return R.drawable.wx_cloud;
        if (code <= 48) return R.drawable.wx_fog;
        if (code <= 57) return R.drawable.wx_drizzle;
        if (code <= 67 || (code >= 80 && code <= 82)) return R.drawable.wx_rain;
        if (code <= 86) return R.drawable.wx_drizzle;   // snow: no snow icon, Dhaka has no use for one
        return R.drawable.wx_storm;
    }

    /** The weather card's tint, top and bottom: the sky it describes, translucent over the wallpaper. */
    static int[] sky(int code, boolean day) {
        if (code >= 95) return new int[]{0xB33A3358, 0x99191628};          // thunderstorm: violet slate
        if (code >= 51) return new int[]{0xB32E4C66, 0x99152433};          // rain, drizzle: steel blue
        if (code >= 45) return new int[]{0xB3525A66, 0x99262B33};          // fog: grey
        if (code == 3) return new int[]{0xB3475366, 0x99212836};           // overcast: slate
        return day ? new int[]{0xB33C6FB0, 0x991A3557}                      // clear by day: sky blue
                : new int[]{0xB3283066, 0x99121633};                       // clear by night: indigo
    }

    // ---------------------------------------------------------------- views

    private LinearLayout card(int[] tint) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(background(tint));
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnFocusChangeListener((v, focused) -> {
            if (focused) onCardFocused.run();
        });
        return c;
    }

    /** Glass in the given tint; focused, the same glass with a clear white rim. No scaling: nothing re-lays out. */
    private Drawable background(int[] tint) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, glass(tint, 2, 0xE6FFFFFF));
        s.addState(new int[]{}, glass(tint, 1, 0x38FFFFFF));
        return s;
    }

    private GradientDrawable glass(int[] tint, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, tint);
        g.setCornerRadius(RADIUS_DP * d);
        g.setStroke(px(strokeDp), strokeColor);
        return g;
    }

    private LinearLayout cell(String top, int iconRes, CharSequence bottom) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(10, DIM, regular);
        t.setText(top);
        c.addView(t);
        ImageView i = icon(18);
        i.setImageResource(iconRes);
        c.addView(i, sized(px(18), px(18), 0, 3, 0, 3));
        TextView b = text(11, SOFT, regular);
        b.setText(bottom);
        c.addView(b);
        return c;
    }

    private LinearLayout row(int gravity) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(gravity);
        return r;
    }

    private TextView text(int sp, int color, Typeface face) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private ImageView icon(int dp) {
        ImageView i = new ImageView(act);
        i.setLayoutParams(new LinearLayout.LayoutParams(px(dp), px(dp)));
        return i;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        return sized(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l, t, r, b);
    }

    private LinearLayout.LayoutParams sized(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    private int px(int dp) {
        return Math.round(dp * d);
    }
}
