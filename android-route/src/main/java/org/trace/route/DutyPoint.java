package org.trace.route;

/** Coordinate and window checks shared by duty-point presentation and capture. */
final class DutyPoint {
  private DutyPoint() { }

  static boolean valid(double lat, double lng) {
    return Double.isFinite(lat) && Double.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
  }

  static boolean inWindow(long fixTime, long start, long end) {
    return fixTime > 0 && (start <= 0 || fixTime >= start) && (end <= 0 || fixTime <= end);
  }

  static double metres(double lat, double lng, double pointLat, double pointLng) {
    double a = Math.sin(Math.toRadians(pointLat - lat) / 2);
    double b = Math.sin(Math.toRadians(pointLng - lng) / 2);
    double h = a * a + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(pointLat)) * b * b;
    return 12742000 * Math.asin(Math.min(1, Math.sqrt(h)));
  }
}
