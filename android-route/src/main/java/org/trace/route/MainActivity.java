package org.trace.route;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  public static final String[] EAST_GODAVARI_PS = {
    "Select Police Station...",
    "I Town L&O PS",
    "II Town L&O PS",
    "III Town L&O PS",
    "Prakash Nagar PS",
    "Bommuru PS",
    "Rajanagaram PS",
    "Kadiyam PS",
    "Dowleswaram PS",
    "Korukonda PS",
    "Gokavaram PS",
    "Seetha Nagaram PS",
    "Anaparthi PS",
    "Biccavolu PS",
    "Rangampeta PS",
    "Kovvuru town PS",
    "Kovvuru rural PS",
    "Chagallu PS",
    "Thallapudi PS",
    "Gopalapuram PS",
    "Devarapalli PS",
    "Nidadavolu PS",
    "Samisragudem PS",
    "Undrajavaram PS",
    "Peravali PS",
    "Nallajarla PS",
    "Mandapeta town PS",
    "Mandapeta rural PS",
    "Angara PS",
    "Rayavaram PS"
  };

  private static final int PERMISSION = 10, PHOTO = 11, STOP = 12;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler ui = new Handler(Looper.getMainLooper());
  private Session session;
  private Outbox outbox;

  // Root view containers for Sign-In screen vs Main App screen
  private LinearLayout signInView;
  private LinearLayout mainAppView;

  // Sign-in inputs
  private Spinner loginPsSpinner, loginRouteSpinner;
  private EditText loginEmail, loginPassword;
  private LinearLayout loginAssignment;
  private JSONObject routeCatalog = new JSONObject();
  private final java.util.ArrayList<JSONObject> routeChoices = new java.util.ArrayList<>();
  private boolean signingIn, checkingUpdate;
  private boolean pendingAutoStart, dutyStartInProgress;
  private long lastSosTap;
  private File downloadedUpdate;
  private TextView assignmentCard;
  private Button navigateButton;
  private JSONObject attendanceCurrent = new JSONObject();
  private long lastAttendanceRead;
  private EditText loginOfficer, loginMobile, loginSd;
  private TextView loginStatus;

  // Main screen components
  private TextView status, message, telemetry, officerBadge;
  private EditText reportNote, votes;
  private Spinner pollingSpinner;
  private JSONObject stationCatalog = new JSONObject();
  private final java.util.ArrayList<JSONObject> pollingChoices = new java.util.ArrayList<>();
  private Button duty, voice;
  private MediaRecorder recorder;
  private File audioFile;
  private boolean recording;
  private final View[] pages = new View[4];
  private final Button[] navButtons = new Button[4];
  private int currentPage = 0;

  private final Runnable telemetryTick = new Runnable() {
    @Override public void run() {
      if (telemetry != null && session != null) {
        String bat = session.get("battery");
        int q = outbox != null ? outbox.size() : 0;
        long fixTime = 0;
        try { fixTime = Long.parseLong(session.get("lastFix")); } catch (NumberFormatException ignored) { }
        boolean freshFix = session.get("duty").equals("1") && fixTime > 0 && System.currentTimeMillis() - fixTime < 25000;
        String speed = freshFix ? " · Speed " + session.get("lastSpeedKmh") + " km/h" : "";
        telemetry.setText(bat.isEmpty()
          ? "Telemetry ready · " + q + " queued"
          : "Battery " + bat + "% · Signal " + session.get("signal") + "/4 · GPS " + session.get("gps") + speed + " · " + q + " queued" + (session.get("syncError").isEmpty() ? "" : " · Upload retry needed"));
        refreshAssignmentCard();
        String dutySession = session.get("sessionId").isEmpty() ? session.get("lastDutySessionId") : session.get("sessionId");
        if (!dutySession.isEmpty() && !session.get("assignment").isEmpty() && System.currentTimeMillis() - lastAttendanceRead >= 30000) {
          lastAttendanceRead = System.currentTimeMillis();
          work(() -> {
            try {
              JSONObject current = session.dbGet("attendance/" + session.uid() + "/" + dutySession);
              ui.post(() -> {
                String currentSession = session.get("sessionId").isEmpty() ? session.get("lastDutySessionId") : session.get("sessionId");
                if (dutySession.equals(currentSession)) { attendanceCurrent = current; refreshAssignmentCard(); }
              });
            } catch (Exception ignored) { }
          });
        }
      }
      ui.postDelayed(this, 5000);
    }
  };

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    session = new Session(this);
    outbox = new Outbox(this);
    try { stationCatalog = new JSONObject(session.get("stationCatalog")); } catch (Exception ignored) { }

    // Auto-configure backend defaults so officers never need to type database URLs or keys
    getWindow().setStatusBarColor(Color.rgb(10, 10, 14));
    getWindow().setNavigationBarColor(Color.rgb(15, 15, 20));

    // Master container
    FrameLayout masterLayout = new FrameLayout(this);
    masterLayout.setBackgroundColor(Color.rgb(10, 10, 14));

    // Top inset padding to avoid camera notch/punch-hole
    int topNotchPadding = dp(46);

    // 1. Build Sign-In View
    signInView = buildSignInScreen(topNotchPadding);
    masterLayout.addView(signInView);

    // 2. Build Main App View
    mainAppView = buildMainAppScreen(topNotchPadding);
    masterLayout.addView(mainAppView);

    setContentView(masterLayout);

    // Check if already signed in
    boolean isSignedIn = session.get("signedIn").equals("1") && session.authenticated();
    if (!isSignedIn) { session.set("duty", "0"); stopService(new Intent(this, TrackService.class)); }
    showScreen(isSignedIn);

    refreshDuty();
    if ("org.trace.route.CONFIRM_STOP".equals(getIntent().getAction())) ui.post(this::toggleDuty);
    ui.post(telemetryTick);
    checkForUpdates(false);
  }

  private void showScreen(boolean inMainApp) {
    if (inMainApp) {
      signInView.setVisibility(View.GONE);
      mainAppView.setVisibility(View.VISIBLE);
      updateOfficerBadge();
      refreshPollingChoices();
      refreshAssignmentCard();
      switchPage(0);
    } else {
      pendingAutoStart = false;
      mainAppView.setVisibility(View.GONE);
      signInView.setVisibility(View.VISIBLE);
    }
  }

  private void updateOfficerBadge() {
    if (officerBadge != null && session != null) {
      String ps = session.get("ps");
      String rt = session.get("route");
      String off = session.get("officer");
      officerBadge.setText((off.isEmpty() ? "Duty Officer" : off) + "  ·  " + ps + (rt.isEmpty() ? "" : " (" + rt + ")"));
    }
  }

  // ==========================================
  // SCREEN 1: SIGN IN PAGE
  // ==========================================
  private LinearLayout buildSignInScreen(int topPadding) {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(22), topPadding, dp(22), dp(24));
    root.setBackgroundColor(Color.rgb(10, 10, 14));

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(box);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

    // Header Badge
    TextView tag = new TextView(this);
    tag.setText("EAST GODAVARI DISTRICT POLICE");
    tag.setTextSize(11);
    tag.setTextColor(Color.rgb(96, 165, 250));
    tag.setTypeface(null, android.graphics.Typeface.BOLD);
    tag.setLetterSpacing(0.12f);
    box.addView(tag);

    TextView title = new TextView(this);
    title.setText("TRACK  /  SIGN IN");
    title.setTextSize(26);
    title.setTextColor(Color.WHITE);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setLetterSpacing(0.06f);
    title.setPadding(0, dp(4), 0, dp(4));
    box.addView(title);

    TextView desc = new TextView(this);
    desc.setText("Patrol Tracking & Emergency Response System");
    desc.setTextSize(13);
    desc.setTextColor(Color.rgb(156, 163, 175));
    desc.setPadding(0, 0, 0, dp(18));
    box.addView(desc);

    loginStatus = new TextView(this);
    loginStatus.setText("Use the mobile number and password given by the control room. With an assigned route, signing in starts active-duty GPS sharing with the control room and your assigned supervisors, including while the screen is locked. Stop Duty ends sharing. Android may ask for precise location.");
    loginStatus.setTextSize(13);
    loginStatus.setTextColor(Color.rgb(52, 211, 153));
    loginStatus.setPadding(0, 0, 0, dp(14));
    box.addView(loginStatus);

    section(box, "1. SIGN IN TO YOUR ACCOUNT");
    loginEmail = field(box, "Mobile number (admin: email)", session.get("login"));
    loginEmail.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    loginPassword = field(box, "Password", "");
    loginPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    button(box, "Sign in and share duty location", this::authenticate);
    loginAssignment = new LinearLayout(this);
    loginAssignment.setOrientation(LinearLayout.VERTICAL);
    box.addView(loginAssignment, new LinearLayout.LayoutParams(-1, -2));
    box = loginAssignment;
    loginAssignment.setVisibility(View.GONE);
    section(box, "YOUR ASSIGNED ROUTE");
    loginPsSpinner = new Spinner(this);
    ArrayAdapter<String> adapter = spinnerAdapter(java.util.Arrays.asList(EAST_GODAVARI_PS));
    loginPsSpinner.setAdapter(adapter);
    loginPsSpinner.setBackground(shape(Color.rgb(24, 27, 36), Color.rgb(59, 130, 246)));
    loginPsSpinner.setPadding(dp(12), dp(12), dp(12), dp(12));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
    sp.bottomMargin = dp(14);
    box.addView(loginPsSpinner, sp);

    String savedPs = session.get("ps");
    if (!savedPs.isEmpty()) {
      for (int i = 0; i < EAST_GODAVARI_PS.length; i++) {
        if (EAST_GODAVARI_PS[i].equalsIgnoreCase(savedPs)) {
          loginPsSpinner.setSelection(i);
          break;
        }
      }
    }

    loginPsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position > 0) {
          String selected = EAST_GODAVARI_PS[position];
          String subDiv = getSubDivision(selected);
          if (loginSd != null) loginSd.setText(subDiv);
          refreshRouteChoices();
        }
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });

    section(box, "OFFICER DETAILS");
    loginSd = field(box, "Sub-Division", session.get("sd").isEmpty() ? "East Godavari" : session.get("sd"));
    loginRouteSpinner = new Spinner(this);
    box.addView(loginRouteSpinner, new LinearLayout.LayoutParams(-1, dp(52)));
    loginOfficer = field(box, "Officer Name & Rank", session.get("officer"));
    loginMobile = field(box, "Officer Mobile Number", session.get("mobile"));

    section(box, "CONTINUE TO DUTY");
    Button enterBtn = new Button(this);
    enterBtn.setText("CONTINUE TO DUTY  ➔");
    enterBtn.setTextSize(15);
    enterBtn.setTypeface(null, android.graphics.Typeface.BOLD);
    enterBtn.setTextColor(Color.WHITE);
    enterBtn.setBackground(shape(Color.rgb(37, 99, 235), Color.rgb(96, 165, 250)));
    LinearLayout.LayoutParams enterP = new LinearLayout.LayoutParams(-1, dp(52));
    enterP.bottomMargin = dp(10);
    box.addView(enterBtn, enterP);
    enterBtn.setOnClickListener(v -> performSignIn());

    button(box, "📷 Scan Route QR Code", () -> new IntentIntegrator(this).setPrompt("Scan Route QR Assignment").initiateScan());

    return root;
  }

  private void authenticate() {
    if (signingIn) return;
    String email = value(loginEmail), password = loginPassword.getText().toString();
    if (email.isEmpty() || password.isEmpty()) { loginStatus.setText("Enter your mobile number and password."); return; }
    loginAssignment.setVisibility(View.GONE);
    signingIn = true; loginStatus.setText("Signing in and loading your assignment…");
    work(() -> {
      try {
        session.signIn(email, password);
        JSONObject available = session.dbGet("config/routes");
        JSONObject sites = session.dbGet("config/stations");
        JSONObject assigned = "route".equals(session.get("role")) ? session.dbGet("assignments/" + session.uid()) : null;
        if (assigned != null && assigned.length() == 0) {
          session.set("assignment", ""); session.set("duty", "0"); session.set("signedIn", "1");
          for (String key : new String[]{"sd", "ps", "route", "officer", "mobile", "el"}) session.set(key, "");
          ui.post(() -> { routeCatalog = new JSONObject(); stationCatalog = new JSONObject(); loginPassword.setText("");
            showScreen(true); note("No duty assigned yet. Ask the control room to assign your route and sign in again."); });
          return;
        }
        if (assigned != null && (assigned.optString("ps").isEmpty() || assigned.optString("route").isEmpty()))
          throw new SecurityException("No route assigned to this account. Contact the control room.");
        if (assigned != null && !assigned.optString("id").equals(new JSONObject(session.get("assignment").isEmpty() ? "{}" : session.get("assignment")).optString("id"))) {
          session.set("lastDutySessionId", ""); attendanceCurrent = new JSONObject();
        }
        session.set("assignment", assigned == null ? "" : assigned.toString());
        JSONObject allowed = new JSONObject();
        java.util.Iterator<String> keys = available.keys();
        while (keys.hasNext()) {
          String id = keys.next(); JSONObject r = available.getJSONObject(id);
          if (assigned == null || (assigned.optString("ps").equals(r.optString("ps")) && assigned.optString("route").equals(r.optString("route")))) allowed.put(id, r);
        }
        if (allowed.length() == 0) throw new SecurityException("No matching route in the control-room list. Ask the control room to check your assignment.");
        if (assigned != null) {
          JSONObject selected = allowed.optJSONObject(assigned.optString("routeId"));
          if (selected == null) selected = allowed.getJSONObject(allowed.keys().next());
          String officer = assigned.optString("officer").trim();
          if (officer.isEmpty()) throw new SecurityException("Your assignment has no officer name. Ask the control room to correct it.");
          JSONObject device = deviceRecord(selected, officer, assigned.optString("mobile"));
          saveOfficerSession(device);
          session.set("stationCatalog", sites.toString());
          ui.post(() -> {
            routeCatalog = allowed; stationCatalog = sites;
            loginPassword.setText("");
            showScreen(true);
            note("Signed in · Starting duty location");
            pendingAutoStart = true;
            resumePendingDuty();
          });
          return;
        }
        ui.post(() -> {
          routeCatalog = allowed; stationCatalog = sites;
          session.set("stationCatalog", sites.toString());
          loginPassword.setText("");
          loginPsSpinner.setEnabled(true);
          loginOfficer.setEnabled(true); loginMobile.setEnabled(true); loginSd.setEnabled(true);
          refreshRouteChoices();
          loginRouteSpinner.setEnabled(true);
          loginAssignment.setVisibility(View.VISIBLE);
          loginStatus.setText("Signed in as administrator. Select a route, then continue.");
        });
      } catch (Exception e) {
        session.clear();
        ui.post(() -> { routeCatalog = new JSONObject(); refreshRouteChoices(); loginStatus.setText("Sign-in failed: " + e.getMessage()); });
      } finally { ui.post(() -> signingIn = false); }
    });
  }

  private void refreshRouteChoices() {
    if (loginRouteSpinner == null) return;
    routeChoices.clear();
    String selected = EAST_GODAVARI_PS[loginPsSpinner.getSelectedItemPosition()];
    java.util.Iterator<String> keys = routeCatalog.keys();
    while (keys.hasNext()) { JSONObject r = routeCatalog.optJSONObject(keys.next()); if (r != null && selected.equals(r.optString("ps"))) routeChoices.add(r); }
    routeChoices.sort((a,b) -> a.optString("route").compareTo(b.optString("route")));
    java.util.ArrayList<String> labels = new java.util.ArrayList<>();
    for (JSONObject r : routeChoices) labels.add(r.optString("route"));
    if (labels.isEmpty()) labels.add("Sign in and select an assigned station");
    loginRouteSpinner.setAdapter(spinnerAdapter(labels));
    loginRouteSpinner.setEnabled(!routeChoices.isEmpty() && !"route".equals(session.get("role")));
  }

  private ArrayAdapter<String> spinnerAdapter(java.util.List<String> labels) {
    return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
      @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
        View v = super.getView(position, convert, parent);
        if (v instanceof TextView) { ((TextView) v).setTextColor(Color.WHITE); ((TextView) v).setTextSize(14); }
        return v;
      }
    };
  }

  private void refreshPollingChoices() {
    if (pollingSpinner == null) return;
    pollingChoices.clear(); java.util.ArrayList<String> labels = new java.util.ArrayList<>();
    java.util.Iterator<String> keys = stationCatalog.keys();
    while (keys.hasNext()) {
      String id = keys.next(); JSONObject p = stationCatalog.optJSONObject(id);
      if (p != null && session.get("ps").equals(p.optString("ps")) && session.get("route").equals(p.optString("route"))) {
        try { p.put("id", id); } catch (Exception ignored) { }
        pollingChoices.add(p); labels.add(p.optString("name"));
      }
    }
    if (labels.isEmpty()) labels.add("No polling locations assigned to this route");
    pollingSpinner.setAdapter(spinnerAdapter(labels)); pollingSpinner.setEnabled(!pollingChoices.isEmpty());
  }

  private void performSignIn() {
    if (signingIn) return;
    if (!session.authenticated() || routeChoices.isEmpty()) { loginStatus.setText("Sign in and select an assigned route first."); return; }
    JSONObject selected = routeChoices.get(loginRouteSpinner.getSelectedItemPosition());
    String officer = value(loginOfficer), mobile = value(loginMobile);
    if (officer.isEmpty()) { loginStatus.setText("Enter your name and rank."); return; }
    signingIn = true; loginStatus.setText("Registering with the control room…");
    work(() -> {
      try {
        // Do not let a previous account's queued writes block this account's location.
        // Older accounts retain their queue; flush only sends the current account’s records.
        JSONObject d = deviceRecord(selected, officer, mobile);
        session.dbPut("devices/" + session.uid(), d);
        saveOfficerSession(d);
        ui.post(() -> { showScreen(true); note("Signed in · Starting duty location"); pendingAutoStart = true; resumePendingDuty(); });
      } catch (Exception e) { ui.post(() -> loginStatus.setText("Registration failed: " + e.getMessage())); }
      finally { ui.post(() -> signingIn = false); }
    });
  }

  private JSONObject deviceRecord(JSONObject route, String officer, String mobile) throws Exception {
    return new JSONObject().put("sd", route.optString("sd")).put("ps", route.optString("ps"))
      .put("route", route.optString("route")).put("officer", officer).put("mobile", mobile)
      .put("force", "route").put("el", route.optString("election")).put("v", BuildConfig.VERSION_NAME);
  }

  private void saveOfficerSession(JSONObject device) {
    for (String key : new String[]{"sd", "ps", "route", "officer", "mobile", "el"}) session.set(key, device.optString(key));
    session.set("signedIn", "1"); session.set("approved", "1");
  }

  // ==========================================
  // SCREEN 2: MAIN APP (4 PAGES + BOTTOM NAV)
  // ==========================================
  private LinearLayout buildMainAppScreen(int topPadding) {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(10, 10, 14));

    // Top Header with notch safety padding
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setPadding(dp(18), topPadding, dp(18), dp(10));
    header.setBackgroundColor(Color.rgb(15, 17, 23));

    TextView title = new TextView(this);
    title.setText("TRACK  ·  ACTIVE PATROL");
    title.setTextSize(19);
    title.setTextColor(Color.WHITE);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setLetterSpacing(0.08f);
    header.addView(title);

    officerBadge = new TextView(this);
    officerBadge.setText("East Godavari District Police");
    officerBadge.setTextSize(12);
    officerBadge.setTextColor(Color.rgb(96, 165, 250));
    header.addView(officerBadge);

    telemetry = new TextView(this);
    telemetry.setText("Telemetry starting…");
    telemetry.setTextSize(12);
    telemetry.setTextColor(Color.rgb(52, 211, 153));
    telemetry.setPadding(0, dp(3), 0, 0);
    header.addView(telemetry);

    status = new TextView(this);
    status.setText("Ready for duty");
    status.setTextSize(13);
    status.setTextColor(Color.rgb(209, 213, 219));
    status.setPadding(0, dp(2), 0, 0);
    header.addView(status);

    root.addView(header);

    // 4 Pages Container
    FrameLayout container = new FrameLayout(this);
    LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
    root.addView(container, containerParams);

    pages[0] = buildDutyPage();
    pages[1] = buildSosPage();
    pages[2] = buildPollingPage();
    pages[3] = buildCommsPage();

    for (int i = 0; i < pages.length; i++) {
      container.addView(pages[i]);
      pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
    }

    // Bottom Navigation Bar - 4 tabs
    LinearLayout navBar = new LinearLayout(this);
    navBar.setOrientation(LinearLayout.HORIZONTAL);
    navBar.setBackgroundColor(Color.rgb(18, 20, 28));
    navBar.setPadding(dp(4), dp(6), dp(4), dp(8));

    String[] navTitles = {"Duty", "SOS", "Polling", "Comms"};
    for (int i = 0; i < 4; i++) {
      final int pageIndex = i;
      Button b = new Button(this);
      b.setText(navTitles[i]);
      b.setTextSize(13);
      b.setTypeface(null, android.graphics.Typeface.BOLD);
      b.setAllCaps(false);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
      p.setMargins(dp(2), 0, dp(2), 0);
      b.setLayoutParams(p);
      b.setOnClickListener(v -> switchPage(pageIndex));
      navButtons[i] = b;
      navBar.addView(b);
    }
    root.addView(navBar);

    return root;
  }

  private void switchPage(int index) {
    currentPage = index;
    for (int i = 0; i < pages.length; i++) {
      pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
    }
    updateNavButtons();
  }

  private void updateNavButtons() {
    for (int i = 0; i < navButtons.length; i++) {
      boolean selected = (i == currentPage);
      boolean isSos = (i == 1);
      if (selected) {
        navButtons[i].setTextColor(Color.WHITE);
        navButtons[i].setBackground(shape(isSos ? Color.rgb(220, 38, 38) : Color.rgb(37, 99, 235), Color.rgb(96, 165, 250)));
      } else {
        navButtons[i].setTextColor(isSos ? Color.rgb(248, 113, 113) : Color.rgb(156, 163, 175));
        navButtons[i].setBackground(shape(Color.rgb(25, 28, 38), Color.rgb(40, 44, 58)));
      }
    }
  }

  private View buildDutyPage() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(18), dp(14), dp(18), dp(20));
    scroll.addView(box);

    section(box, "MY DUTY");
    duty = new Button(this);
    duty.setText("START DUTY");
    duty.setTextSize(16);
    duty.setTypeface(null, android.graphics.Typeface.BOLD);
    duty.setTextColor(Color.WHITE);
    duty.setBackground(shape(Color.rgb(34, 197, 94), Color.rgb(255, 255, 255)));
    LinearLayout.LayoutParams dpParam = new LinearLayout.LayoutParams(-1, dp(58));
    dpParam.bottomMargin = dp(14);
    box.addView(duty, dpParam);
    duty.setOnClickListener(v -> toggleDuty());

    section(box, "ASSIGNED WORK");
    TextView unitCard = new TextView(this);
    assignmentCard = unitCard;
    unitCard.setText("Station: " + session.get("ps") + "\nRoute: " + session.get("route") + "\nOfficer: " + session.get("officer"));
    unitCard.setTextSize(14);
    unitCard.setTextColor(Color.WHITE);
    unitCard.setBackground(shape(Color.rgb(24, 27, 36), Color.rgb(51, 65, 85)));
    unitCard.setPadding(dp(14), dp(14), dp(14), dp(14));
    LinearLayout.LayoutParams ucP = new LinearLayout.LayoutParams(-1, -2);
    ucP.bottomMargin = dp(14);
    box.addView(unitCard, ucP);
    navigateButton = button(box, "Navigate to duty point", this::navigateToDutyPoint);
    refreshAssignmentCard();

    section(box, "OFFICER SESSION & UPDATES");
    button(box, "Switch Officer / Change Police Station", () -> {
      if (session.get("duty").equals("1")) {
        note("Stop Duty before switching accounts");
        return;
      }
      session.clear();
      routeCatalog = new JSONObject(); refreshRouteChoices();
      showScreen(false);
    });
    button(box, "🚀 Check for App Updates", () -> checkForUpdates(true));
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      button(box, "Allow tracking notifications (optional)", this::explainNotifications);

    return scroll;
  }

  private View buildSosPage() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(18), dp(14), dp(18), dp(20));
    scroll.addView(box);

    section(box, "EMERGENCY ALERT DISPATCH");
    Button sosBtn = new Button(this);
    sosBtn.setText("🚨  SOS · REQUEST HELP");
    sosBtn.setTextSize(18);
    sosBtn.setTypeface(null, android.graphics.Typeface.BOLD);
    sosBtn.setTextColor(Color.WHITE);
    sosBtn.setBackground(shape(Color.rgb(220, 38, 38), Color.rgb(239, 68, 68)));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(64));
    sp.bottomMargin = dp(16);
    box.addView(sosBtn, sp);
    sosBtn.setOnClickListener(v -> sos());

    section(box, "QUICK SITUATION UPDATE");
    button(box, "🟢 Report: Normal", () -> report("normal"));
    button(box, "🟡 Report: Tension", () -> report("tension"));
    button(box, "🔴 Report: Problem / Conflict", () -> report("problem"));
    button(box, "📍 Reached Incident Spot", () -> report("reached"));

    return scroll;
  }

  private View buildPollingPage() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(18), dp(14), dp(18), dp(20));
    scroll.addView(box);

    section(box, "POLLING LOCATION REPORTING");
    pollingSpinner = new Spinner(this);
    box.addView(pollingSpinner, new LinearLayout.LayoutParams(-1, dp(60)));
    votes = field(box, "Cumulative Votes Polled (Turnout)", "");
    votes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    reportNote = field(box, "Incident / Observation Details", "");

    button(box, "📊 Send Turnout Count", () -> report("votes"));
    button(box, "📝 Submit Situation Report", () -> report("loc"));

    return scroll;
  }

  private View buildCommsPage() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(18), dp(14), dp(18), dp(20));
    scroll.addView(box);

    section(box, "FIELD MEDIA & DISPATCH");
    button(box, "📷 Take and Send Photo", () -> startActivityForResult(new Intent("android.media.action.IMAGE_CAPTURE"), PHOTO));
    voice = button(box, "🎤 Record Voice Message", () -> toggleRecording());
    button(box, "▶ Play Latest Voice Message", () -> playVoice());

    section(box, "CONTROL ROOM FEED");
    message = addText(box, "No new control room messages.", 14);
    message.setBackground(shape(Color.rgb(24, 27, 36), Color.rgb(55, 65, 81)));
    message.setPadding(dp(12), dp(12), dp(12), dp(12));

    return scroll;
  }

  private String getSubDivision(String psName) {
    if (psName.contains("I Town") || psName.contains("II Town") || psName.contains("III Town") || psName.contains("Prakash Nagar") || psName.contains("Bommuru"))
      return "Rajahmundry Urban";
    if (psName.contains("Rajanagaram") || psName.contains("Kadiyam") || psName.contains("Dowleswaram") || psName.contains("Korukonda") || psName.contains("Gokavaram") || psName.contains("Seetha Nagaram"))
      return "Rajahmundry Rural";
    if (psName.contains("Kovvuru") || psName.contains("Chagallu") || psName.contains("Thallapudi") || psName.contains("Gopalapuram") || psName.contains("Devarapalli") || psName.contains("Nidadavolu") || psName.contains("Samisragudem") || psName.contains("Undrajavaram") || psName.contains("Peravali") || psName.contains("Nallajarla"))
      return "Kovvur Sub-division";
    if (psName.contains("Anaparthi") || psName.contains("Biccavolu") || psName.contains("Rangampeta") || psName.contains("Mandapeta") || psName.contains("Angara") || psName.contains("Rayavaram"))
      return "Mandapeta Sub-division";
    return "East Godavari";
  }

  private int dp(int n) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n, getResources().getDisplayMetrics())); }
  private GradientDrawable shape(int fill, int border) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(8)); d.setStroke(dp(1), border); return d; }
  private void section(LinearLayout box, String label) { TextView t = addText(box, label, 11); t.setTextColor(Color.rgb(148, 163, 184)); t.setLetterSpacing(0.12f); t.setTypeface(null, android.graphics.Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); }
  private TextView addText(LinearLayout box, String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(226, 232, 240)); v.setPadding(0, dp(4), 0, dp(4)); box.addView(v); return v; }

  private EditText field(LinearLayout box, String hint, String value) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setHint(hint);
    e.setText(value);
    e.setTextSize(14);
    e.setTextColor(Color.WHITE);
    e.setHintTextColor(Color.rgb(148, 163, 184));
    e.setPadding(dp(14), dp(12), dp(14), dp(12));
    e.setBackground(shape(Color.rgb(24, 27, 36), Color.rgb(51, 65, 85)));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(9);
    box.addView(e, p);
    return e;
  }

  private Button button(LinearLayout box, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTypeface(null, android.graphics.Typeface.BOLD);
    boolean sos = label.contains("SOS");
    b.setTextColor(Color.WHITE);
    b.setBackground(shape(sos ? Color.rgb(220, 38, 38) : Color.rgb(30, 41, 59),
                          sos ? Color.rgb(239, 68, 68) : Color.rgb(71, 85, 105)));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
    p.bottomMargin = dp(9);
    box.addView(b, p);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private String value(EditText e) { return e == null ? "" : e.getText().toString().trim(); }
  private void work(Runnable r) { io.execute(r); }
  private void note(String s) { ui.post(() -> status.setText(s)); }
  private void checkForUpdates(boolean userInitiated) {
    if (checkingUpdate) return;
    checkingUpdate = true;
    if (userInitiated) note("Checking for updates…");
    work(() -> {
      java.net.HttpURLConnection conn = null;
      try {
        conn = (java.net.HttpURLConnection) new java.net.URL("https://raw.githubusercontent.com/abdulaleemarshad1979/TRACE/main/version.json").openConnection();
        conn.setConnectTimeout(8000); conn.setReadTimeout(8000); conn.setUseCaches(false);
        conn.setRequestProperty("Cache-Control", "no-cache");
        if (conn.getResponseCode() != 200) throw new java.io.IOException("Update server unavailable");
        JSONObject v;
        try (java.io.InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] buf = new byte[4096]; int n;
          while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 65536) throw new java.io.IOException("Invalid update manifest"); }
          v = new JSONObject(out.toString("UTF-8"));
        }
        int remoteCode = v.getInt("versionCode");
        String apkUrl = v.getString("apkUrl");
        if (!UpdatePolicy.validDownloadUrl(apkUrl) || remoteCode <= 0) throw new java.io.IOException("Invalid update manifest");
        if (UpdatePolicy.isNewer(BuildConfig.VERSION_CODE, remoteCode)) {
          if (userInitiated || !session.get("dismissedUpdate").equals(String.valueOf(remoteCode)))
            ui.post(() -> { if (!isFinishing() && !isDestroyed()) showUpdateDialog(v.optString("versionName"), v.optString("releaseNotes"), apkUrl, v.optString("sha256"), remoteCode); });
        } else if (userInitiated) note("TRACK is up to date (v" + BuildConfig.VERSION_NAME + ")");
      } catch (Exception e) { if (userInitiated) note("Could not check for updates. Check your internet connection and try again."); }
      finally { if (conn != null) conn.disconnect(); ui.post(() -> checkingUpdate = false); }
    });
  }

  private void showUpdateDialog(String versionName, String notes, String apkUrl, String expectedSha256, int remoteCode) {
    new AlertDialog.Builder(this)
      .setTitle("🚀 Update Available (v" + versionName + ")")
      .setMessage(notes + "\n\nA new update is ready on GitHub. Would you like to update now?")
      .setCancelable(true)
      .setPositiveButton("Update Now", (d, w) -> downloadUpdate(apkUrl, expectedSha256))
      .setNegativeButton("Later", (d, w) -> session.set("dismissedUpdate", String.valueOf(remoteCode)))
      .show();
  }

  private void downloadUpdate(String apkUrl, String expectedSha256) {
    note("Downloading verified update…");
    work(() -> {
      HttpURLConnection conn = null;
      File part = new File(new File(getCacheDir(), "updates"), "track-update.part");
      try {
        if (!UpdatePolicy.validDownloadUrl(apkUrl) || !expectedSha256.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid update manifest");
        if (!part.getParentFile().isDirectory() && !part.getParentFile().mkdirs()) throw new java.io.IOException("Could not prepare download folder");
        conn = (HttpURLConnection) new URL(apkUrl).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(30000);
        if (conn.getResponseCode() != 200) throw new java.io.IOException("Update download failed: HTTP " + conn.getResponseCode());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (java.io.InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
          byte[] buf = new byte[8192]; int n;
          while ((n = in.read(buf)) != -1) { total += n; if (total > 100_000_000) throw new java.io.IOException("Update file too large"); digest.update(buf, 0, n); out.write(buf, 0, n); }
        }
        StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (total < 100_000 || !hex.toString().equalsIgnoreCase(expectedSha256)) throw new java.io.IOException("Update file failed verification");
        File apk = new File(part.getParentFile(), "track-update.apk");
        if (apk.exists() && !apk.delete()) throw new java.io.IOException("Could not replace old download");
        if (!part.renameTo(apk)) throw new java.io.IOException("Could not prepare update");
        downloadedUpdate = apk;
        ui.post(() -> openDownloadedUpdate());
      } catch (Exception e) { part.delete(); note("Update failed: " + e.getMessage()); }
      finally { if (conn != null) conn.disconnect(); }
    });
  }

  private void openDownloadedUpdate() {
    if (downloadedUpdate == null || !downloadedUpdate.isFile()) return;
    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
      note("Allow TRACK to install updates, then return here");
      startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName())));
      return;
    }
    try {
      android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", downloadedUpdate);
      Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(install);
      downloadedUpdate = null;
      note("Install the update in Android's installer");
    } catch (Exception e) { note("Could not open Android installer: " + e.getMessage()); }
  }

  private void askPermissions() {
    java.util.ArrayList<String> p = new java.util.ArrayList<>();
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      p.add(Manifest.permission.ACCESS_FINE_LOCATION);
      p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    }
    if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), PERMISSION);
  }

  private void explainNotifications() {
    if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
    new AlertDialog.Builder(this).setTitle("Tracking notification")
      .setMessage("Android may hide the duty notification if notifications are denied. Duty tracking can still run; use My Duty or Android's Active apps panel to stop it.")
      .setNegativeButton("Continue without alerts", null)
      .setPositiveButton("Allow notifications", (d, w) -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION)).show();
  }

  private JSONObject dutyPoint() {
    try { return new JSONObject(session.get("assignment")).optJSONObject("dutyPoint"); }
    catch (Exception ignored) { return null; }
  }

  private void refreshAssignmentCard() {
    if (assignmentCard == null) return;
    if (session.get("assignment").isEmpty()) {
      assignmentCard.setText("No duty assigned yet. Ask the control room for your route or point assignment.");
      if (navigateButton != null) navigateButton.setEnabled(false);
      return;
    }
    JSONObject point = dutyPoint();
    String work = "Officer: " + session.get("officer") + "\nStation: " + session.get("ps") + "\nRoute: " + session.get("route");
    if (point == null) work = "No duty point allotted. Your route duty remains available. Contact the control room if a point was expected.\n" + work;
    else work = "Duty point: " + point.optString("name", "Unnamed point") + "\n" + work
      + "\nInstructions: " + point.optString("instructions", "Follow control-room instructions")
      + "\nPresence: " + ownPresence();
    long t = 0; try { t = Long.parseLong(session.get("lastFix")); } catch (Exception ignored) { }
    if (t > 0) work += "\nMy last GPS fix: " + android.text.format.DateFormat.format("dd MMM HH:mm", t)
      + " (" + Math.max(0, (System.currentTimeMillis() - t) / 1000) + " seconds ago)";
    try { work += "\nMy last position: " + String.format(java.util.Locale.ROOT, "%.5f, %.5f", Double.parseDouble(session.get("lastLat")), Double.parseDouble(session.get("lastLng"))); }
    catch (Exception ignored) { }
    assignmentCard.setText(work);
    if (navigateButton != null) navigateButton.setEnabled(point != null && DutyPoint.valid(point.optDouble("lat", Double.NaN), point.optDouble("lng", Double.NaN)));
  }

  private String ownPresence() {
    if (attendanceCurrent.length() == 0) return "Not arrived (no confirmed device-location evidence)";
    JSONObject point = dutyPoint();
    if (point == null || !point.optString("id").equals(attendanceCurrent.optString("pointId"))) return "Not arrived";
    String state = attendanceCurrent.optString("current", "not_arrived");
    long fix = attendanceCurrent.optLong("latestFixTime"), arrival = attendanceCurrent.optLong("arrivalAt");
    if (arrival > 0 && (!session.get("duty").equals("1") || fix <= 0 || System.currentTimeMillis() - fix > 90000)) state = "uncertain";
    String label = "at".equals(state) ? "At duty point" : "left".equals(state) ? "Left duty point"
      : "uncertain".equals(state) ? "Location uncertain" : "Not arrived";
    return label + (arrival > 0 ? " · First arrival " + formatIst(arrival) : "");
  }

  private String formatIst(long timestamp) {
    java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("dd MMM HH:mm 'IST'", java.util.Locale.ENGLISH);
    format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
    return format.format(new java.util.Date(timestamp));
  }

  private void navigateToDutyPoint() {
    JSONObject point = dutyPoint();
    if (point == null) { note("No duty point allotted. Ask the control room for an assignment."); return; }
    double lat = point.optDouble("lat", Double.NaN), lng = point.optDouble("lng", Double.NaN);
    if (!DutyPoint.valid(lat, lng)) { note("Duty point coordinates are invalid. Ask the control room to correct them."); return; }
    Uri destination = Uri.parse("geo:0,0?q=" + lat + "," + lng + "(" + Uri.encode(point.optString("name", "Duty point")) + ")");
    try { startActivity(new Intent(Intent.ACTION_VIEW, destination)); }
    catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + lat + "%2C" + lng))); }
  }

  private void refreshDuty() {
    boolean active = session.get("duty").equals("1");
    if (duty != null) {
      duty.setText(dutyStartInProgress ? "STARTING GPS…" : active ? (TrackService.running ? "STOP DUTY" : "RESUME DUTY") : "START DUTY");
      duty.setEnabled(!dutyStartInProgress);
      duty.setBackground(shape(active ? Color.rgb(239, 68, 68) : Color.rgb(34, 197, 94), Color.rgb(255, 255, 255)));
      duty.setTextColor(Color.WHITE);
    }
  }

  private void toggleDuty() {
    if (dutyStartInProgress) return;
    if (session.get("duty").equals("1") && TrackService.running) {
      KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
      if (km != null && km.isDeviceSecure()) {
        startActivityForResult(km.createConfirmDeviceCredentialIntent("Stop TRACK duty", "Confirm device credential to stop sharing location"), STOP);
        return;
      }
      new AlertDialog.Builder(this)
        .setTitle("Stop Duty")
        .setMessage("Are you sure you want to stop tracking?")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Stop duty", (d, w) -> stopDuty())
        .show();
      return;
    }

    if (!session.authenticated()) {
      note("Sign in first."); showScreen(false);
      return;
    }
    if (session.get("ps").isEmpty() || session.get("assignment").isEmpty()) {
      note("No duty assigned yet. Ask the control room to assign your route.");
      return;
    }

    boolean resume = session.get("duty").equals("1");
    new AlertDialog.Builder(this).setTitle(resume ? "Resume interrupted duty" : "Share location during duty")
      .setMessage("Your GPS location and route trail are sent to the control room and officials assigned to your reporting scope while duty is active, including when the screen is locked or you switch apps. Stop Duty ends sharing. Precise location is needed for route and duty-point tracking.")
      .setNegativeButton("Cancel", null).setPositiveButton("Continue", (d, w) -> prepareDuty(resume)).show();
  }

  private void prepareDuty(boolean resume) {
    if (dutyStartInProgress) return;
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      pendingAutoStart = true;
      askPermissions(); note("Grant precise location to start duty sharing. If only approximate is allowed, change it in app settings."); return;
    }

    android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
    if (lm == null || !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
      pendingAutoStart = true;
      note("Turn on GPS before starting duty");
      startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }

    final String requestedUid = session.uid();
    final String originalAssignment = session.get("assignment");
    dutyStartInProgress = true;
    refreshDuty();
    work(() -> {
      try {
        JSONObject assigned = session.dbGet("assignments/" + requestedUid);
        if (assigned.length() == 0 || !assigned.optString("ps").equals(session.get("ps")) || !assigned.optString("route").equals(session.get("route")))
          throw new SecurityException("Your duty assignment changed. Sign in again or contact the control room.");
        if (resume && !assigned.optString("id").equals(new JSONObject(originalAssignment).optString("id")))
          throw new SecurityException("Your duty assignment changed. Sign in again before resuming.");
        ui.post(() -> {
          dutyStartInProgress = false;
          if (!requestedUid.equals(session.uid()) || !session.authenticated() || !"1".equals(session.get("signedIn"))) { refreshDuty(); return; }
          pendingAutoStart = false;
          session.set("assignment", assigned.toString());
          if (!resume || session.get("sessionId").isEmpty()) {
            session.set("sessionId", UUID.randomUUID().toString());
            session.set("sessionStartedAt", String.valueOf(System.currentTimeMillis()));
            session.set("lastDutySessionId", session.get("sessionId"));
            attendanceCurrent = new JSONObject();
          } else if (session.get("sessionStartedAt").isEmpty()) session.set("sessionStartedAt", String.valueOf(System.currentTimeMillis()));
          session.set("duty", "1");
          try { startForegroundService(new Intent(this, TrackService.class)); }
          catch (Exception e) { session.set("duty", "0"); refreshDuty(); note("Tracking could not start: " + e.getMessage()); return; }
          if (!resume) queueEvent("start"); note("Duty active · Tracking started"); refreshAssignmentCard(); refreshDuty();
        });
      } catch (Exception e) { ui.post(() -> { dutyStartInProgress = false; pendingAutoStart = false; refreshDuty(); note("Cannot start duty: " + e.getMessage()); }); }
    });
  }

  private void resumePendingDuty() {
    if (!pendingAutoStart || dutyStartInProgress || !session.authenticated() || !"1".equals(session.get("signedIn"))) return;
    pendingAutoStart = false;
    prepareDuty("1".equals(session.get("duty")));
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != PERMISSION || !pendingAutoStart) return;
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) resumePendingDuty();
    else if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      note("Approximate location cannot confirm your duty point. Allow precise location in app settings; sharing starts when you return.");
      startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:" + getPackageName())));
    } else { pendingAutoStart = false; note("Location access was denied. Use Start Duty after enabling precise location in app settings."); }
  }

  private void stopDuty() {
    pendingAutoStart = false;
    session.set("lastDutySessionId", session.get("sessionId"));
    session.set("duty", "0");
    stopService(new Intent(this, TrackService.class));
    outbox.removePath("live/" + session.uid());
    queueEvent("stop");
    try {
      JSONObject ended = new JSONObject().put("up", System.currentTimeMillis()).put("receivedAt", new JSONObject().put(".sv", "timestamp"))
        .put("duty", false).put("gps", false).put("sessionId", session.get("sessionId"));
      String assignmentId = new JSONObject(session.get("assignment")).optString("id");
      if (!assignmentId.isEmpty()) ended.put("assignmentId", assignmentId);
      try { ended.put("lat", Double.parseDouble(session.get("lastLat"))).put("lng", Double.parseDouble(session.get("lastLng"))).put("t", Long.parseLong(session.get("lastFix"))); }
      catch (Exception ignored) { }
      outbox.add("live/" + session.uid(), ended);
      work(() -> { try { outbox.flush(session); } catch (Exception ignored) {} });
    } catch (Exception ignored) {}
    session.set("sessionId", "");
    session.set("sessionStartedAt", "");
    note("Duty stopped");
    refreshDuty();
  }

  private String id() { return Long.toString(System.currentTimeMillis(), 36) + UUID.randomUUID().toString().substring(0, 8); }

  private void queueEvent(String type) {
    if (session.uid().isEmpty()) return;
    try {
      JSONObject e = new JSONObject()
        .put("type", type)
        .put("uid", session.uid())
        .put("t", System.currentTimeMillis())
        .put("ps", session.get("ps"))
        .put("route", session.get("route"))
        .put("sessionId", session.get("sessionId"));
      if (type.equals("start") || type.equals("stop")) {
        JSONObject assigned = new JSONObject(session.get("assignment"));
        JSONObject point = assigned.optJSONObject("dutyPoint");
        e.put("assignmentId", assigned.optString("id"))
          .put("pointId", point == null ? "" : point.optString("id"))
          .put("startedAt", Long.parseLong(session.get("sessionStartedAt")));
      }
      if (!session.get("lastLat").isEmpty()) {
        e.put("lat", Double.parseDouble(session.get("lastLat")));
        e.put("lng", Double.parseDouble(session.get("lastLng")));
      }
      String path = "events/" + id();
      outbox.add(path, e);
      work(() -> {
        if (type.equals("sos")) {
          try {
            session.dbPut(path, e);
            outbox.removePath(path);
            note("SOS delivered to Control Room");
          } catch (Exception x) {
            note("SOS queued offline · " + outbox.size() + " pending");
            return;
          }
        }
        try {
          outbox.flush(session);
          if (!type.equals("sos")) note(type.toUpperCase() + " delivered");
        } catch (Exception x) {
          if (!type.equals("sos")) note(type.toUpperCase() + " queued offline");
        }
      });
    } catch (Exception e) { note(e.getMessage()); }
  }

  private void sos() {
    if (!session.authenticated() || session.uid().isEmpty()) { note("Sign in before sending SOS"); return; }
    long now = System.currentTimeMillis();
    if (now - lastSosTap < 3000) return;
    lastSosTap = now;
    try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception ignored) { }
    note("Sending SOS to Control Room…");
    queueEvent("sos");
  }

  private void report(String type) {
    if (session.uid().isEmpty()) { note("Sign in first"); return; }
    try {
      JSONObject location = pollingChoices.isEmpty() ? new JSONObject() : pollingChoices.get(pollingSpinner.getSelectedItemPosition());
      String name = location.optString("name");
      String detail = value(reportNote);
      if (name.isEmpty() && !type.equals("reached") && !type.equals("normal") && !type.equals("tension") && !type.equals("problem")) {
        note("Enter polling station or location");
        return;
      }
      long t = System.currentTimeMillis();
      JSONObject e = new JSONObject()
        .put("type", type.equals("votes") || type.equals("reached") ? type : "loc")
        .put("uid", session.uid())
        .put("t", t)
        .put("ps", session.get("ps"))
        .put("sid", location.optString("id"))
        .put("sname", name)
        .put("note", detail);

      if (!type.equals("votes") && !type.equals("reached")) e.put("status", type);
      if (type.equals("votes")) {
        int count = Integer.parseInt(value(votes));
        if (count < 0) throw new IllegalArgumentException("Votes cannot be negative");
        e.put("n", count);
      }
      if (!session.get("lastLat").isEmpty()) {
        e.put("lat", Double.parseDouble(session.get("lastLat")));
        e.put("lng", Double.parseDouble(session.get("lastLng")));
      }

      outbox.add("events/" + id(), e);
      note("Report queued");
      work(() -> {
        try {
          outbox.flush(session);
          note("Report delivered");
        } catch (Exception ex) {
          note("Report queued offline");
        }
      });
    } catch (Exception e) { note("Report failed: " + e.getMessage()); }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    IntentResult scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
    if (scan != null && scan.getContents() != null) {
      try {
        JSONObject q = new JSONObject(scan.getContents());
        if (q.has("ps")) {
          String psVal = q.optString("ps");
          for (int i = 0; i < EAST_GODAVARI_PS.length; i++) {
            if (EAST_GODAVARI_PS[i].equalsIgnoreCase(psVal)) {
              if (loginPsSpinner != null) loginPsSpinner.setSelection(i);
              break;
            }
          }
        }
        if (q.has("sd") && loginSd != null) loginSd.setText(q.optString("sd"));
        refreshRouteChoices();
        for (int i = 0; i < routeChoices.size(); i++) if (routeChoices.get(i).optString("route").equals(q.optString("r"))) loginRouteSpinner.setSelection(i);
        if (loginStatus != null) loginStatus.setText("Route QR loaded: " + q.optString("ps"));
      } catch (Exception e) {
        if (loginStatus != null) loginStatus.setText("Invalid route QR");
      }
      return;
    }

    if (requestCode == STOP) {
      if (resultCode == RESULT_OK) stopDuty();
      else note("Stop Duty cancelled");
      return;
    }

    if (requestCode != PHOTO || resultCode != RESULT_OK || data == null || data.getExtras() == null || !(data.getExtras().get("data") instanceof Bitmap)) return;
    try {
      Bitmap b = (Bitmap) data.getExtras().get("data");
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      b.compress(Bitmap.CompressFormat.JPEG, 75, stream);
      String pid = id();
      long t = System.currentTimeMillis();
      JSONObject photo = new JSONObject()
        .put("uid", session.uid())
        .put("t", t)
        .put("ps", session.get("ps"))
        .put("img", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP));
      JSONObject event = new JSONObject().put("type", "photo").put("uid", session.uid()).put("t", t).put("pid", pid);
      outbox.add("photos/" + pid, photo);
      outbox.add("events/" + id(), event);
      note("Photo queued");
      work(() -> {
        try {
          outbox.flush(session);
          note("Photo delivered");
        } catch (Exception e) {
          note("Photo queued offline");
        }
      });
    } catch (Exception e) { note("Photo failed: " + e.getMessage()); }
  }

  private void toggleRecording() {
    if (recording) {
      try {
        recorder.stop();
        recorder.release();
        recording = false;
        voice.setText("🎤 Record Voice Message");
        byte[] bytes = Files.readAllBytes(audioFile.toPath());
        if (bytes.length > 900000) {
          note("Recording too large; keep under 1 min");
          return;
        }
        long t = System.currentTimeMillis();
        String vid = id();
        JSONObject v = new JSONObject()
          .put("uid", session.uid())
          .put("a", Base64.encodeToString(bytes, Base64.NO_WRAP))
          .put("mime", "audio/mp4")
          .put("t", t)
          .put("by", session.get("officer"));
        JSONObject e = new JSONObject().put("type", "msg").put("uid", session.uid()).put("t", t).put("voice", vid).put("text", "Voice message from patrol");
        outbox.add("voice/" + vid, v);
        outbox.add("events/" + id(), e);
        note("Voice message queued");
        work(() -> {
          try {
            outbox.flush(session);
            note("Voice message delivered");
          } catch (Exception ex) {
            note("Voice queued offline");
          }
        });
      } catch (Exception e) {
        recording = false;
        note("Recording failed: " + e.getMessage());
      }
      return;
    }

    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION);
      note("Allow microphone access");
      return;
    }

    try {
      audioFile = new File(getCacheDir(), "voice.m4a");
      recorder = new MediaRecorder();
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      recorder.setOutputFile(audioFile.getAbsolutePath());
      recorder.prepare();
      recorder.start();
      recording = true;
      voice.setText("⏹ Stop and Send Recording");
      note("Recording audio…");
    } catch (Exception e) { note("Microphone failed: " + e.getMessage()); }
  }

  private void playVoice() {
    String vid = session.get("lastVoice");
    if (vid.isEmpty()) { note("No voice reply available"); return; }
    work(() -> {
      try {
        JSONObject v = session.dbGet("voice/" + vid);
        byte[] bytes = Base64.decode(v.getString("a"), Base64.DEFAULT);
        File f = new File(getCacheDir(), "reply.m4a");
        Files.write(f.toPath(), bytes);
        ui.post(() -> {
          try {
            android.media.MediaPlayer p = new android.media.MediaPlayer();
            p.setDataSource(f.getAbsolutePath());
            p.prepare();
            p.start();
            p.setOnCompletionListener(android.media.MediaPlayer::release);
            note("Playing dispatch voice audio");
          } catch (Exception e) { note("Playback failed: " + e.getMessage()); }
        });
      } catch (Exception e) { note("Voice download failed"); }
    });
  }

  @Override protected void onResume() {
    super.onResume();
    if (downloadedUpdate != null && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) openDownloadedUpdate();
    if (pendingAutoStart && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
      if (lm != null && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) resumePendingDuty();
    }
    if (message != null) {
      message.setText(session.get("lastMsg").isEmpty() ? "No new control room messages." : session.get("lastMsg"));
    }
    refreshAssignmentCard(); refreshDuty();
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent); setIntent(intent);
    if ("org.trace.route.CONFIRM_STOP".equals(intent.getAction())) toggleDuty();
  }

  @Override protected void onDestroy() {
    ui.removeCallbacks(telemetryTick);
    io.shutdown();
    super.onDestroy();
  }
}
