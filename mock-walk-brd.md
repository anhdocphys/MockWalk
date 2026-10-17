# PROJECT SPECIFICATION: Android Strava Realistic Walk Simulator (with OSRM Routing)

## 1. Context & Objective
Hãy xây dựng một ứng dụng Android hoàn chỉnh bằng ngôn ngữ **Kotlin** để chạy trên thiết bị **Samsung Galaxy S24 Ultra (Android 14/15 - One UI, Target SDK 34)**. 
Ứng dụng hoạt động ở chế độ Debug (được cấp quyền `Select mock location app` trong Developer Options), có nhiệm vụ giả lập chuyển động của một người đi bộ bám sát theo đường xá thực tế thông qua **OSRM API** và bơm dữ liệu GPS giả lập vào hệ thống để ứng dụng **Strava** ghi nhận thành một hoạt động đi bộ (Walk) hợp lệ, tự nhiên.

---

## 2. Tech Stack & Libraries
- **Language:** Kotlin (Coroutines cho xử lý bất đồng bộ và vòng lặp thời gian thực).
- **Min SDK:** 29 | **Target & Compile SDK:** 34.
- **Map UI:** Google Maps SDK for Android (`com.google.android.gms:play-services-maps`).
- **Location Engine:** Google Play Services Location (`com.google.android.gms:play-services-location`) kết hợp Android `LocationManager`.
- **Networking:** `OkHttp` hoặc `Retrofit` + `Gson` (hoặc `org.json`) để gọi OSRM Routing API.

---

## 3. Core Functional Requirements

### 3.1. Giao diện người dùng (`MainActivity`)
1. **Bản đồ tương tác (GoogleMap):**
   - Cho phép người dùng chạm (tap) lên bản đồ để chọn **Điểm bắt đầu (Start Point)** và hiển thị Marker tại đó.
   - Sau khi lấy được tuyến đường từ OSRM, vẽ đường `Polyline` hiển thị lộ trình dự kiến lên bản đồ và cập nhật vị trí nhân vật theo thời gian thực.
2. **Bảng điều khiển đầu vào (Input Controls):**
   - `Min Speed (km/h)`: Tốc độ đi bộ tối thiểu (mặc định: `4.0`).
   - `Max Speed (km/h)`: Tốc độ đi bộ tối đa (mặc định: `6.0`).
   - `Target Distance (km)`: Tổng quãng đường cần di chuyển (mặc định: `3.0`).
3. **Nút điều khiển & Trạng thái:**
   - Nút `Start Simulation` và `Stop Simulation`.
   - Hiển thị thông số thời gian thực trên UI: Quãng đường đã đi / Tổng quãng đường (km), Tốc độ hiện tại (km/h), Pace hiện tại (phút/km).

### 3.2. Tự động sinh lộ trình thực tế qua OSRM API (Không cần API Key)
Vì người dùng chỉ chọn **1 điểm bắt đầu** và nhập **Tổng quãng đường (`Target Distance`)**:
1. **Waypoint Generator:** Từ tọa độ điểm bắt đầu `(startLat, startLng)`, viết thuật toán tự động tính toán một chuỗi các điểm neo (waypoints) tạo thành một vòng lặp (loop) hoặc đa giác xung quanh điểm xuất phát sao cho tổng chu vi ước tính lớn hơn hoặc bằng `Target Distance`.
2. **OSRM API Call:** Gửi request HTTP GET tới public server của OSRM:
   `https://router.project-osrm.org/route/v1/foot/{lng1},{lat1};{lng2},{lat2};...?overview=full&geometries=geojson`
   *(Lưu ý: OSRM nhận thứ tự `longitude,latitude`)*.
3. **Route Parsing:** Giải mã mảng `coordinates` từ GeoJSON trả về thành danh sách `List<LatLng>` chi tiết bám sát các con đường/vỉa hè thực tế. Nếu tổng chiều dài tuyến đường trả về ngắn hơn `Target Distance`, cho phép lặp lại hoặc nối tiếp lộ trình cho đến khi đi đủ `Target Distance` thì tự động dừng.

### 3.3. Thuật toán Giả lập Động học & Độ nhấp nhô tự nhiên (Strava Anti-Filter)
Để Strava công nhận là hoạt động thật và vẽ biểu đồ Pace/Elevation tự nhiên, vòng lặp mô phỏng (cập nhật mỗi `~1000ms` kèm sai số ngẫu nhiên `±30ms`) phải nội suy vị trí dọc theo các đoạn thẳng nối giữa các điểm `LatLng` của OSRM với các biến thiên vật lý sau:
1. **Biến thiên vận tốc có quán tính (Inertial Speed Random Walk):**
   - Chuyển đổi `minSpeed` và `maxSpeed` từ `km/h` sang `m/s`.
   - Không nhảy tốc độ đột ngột. Mỗi giây cập nhật: `currentSpeed = (currentSpeed + randomAccel + cadenceWave).coerceIn(minSpeedMps, maxSpeedMps)` với `randomAccel` trong khoảng `[-0.12, +0.12] m/s²` và `cadenceWave` là hàm `sin()` biên độ nhỏ mô phỏng nhịp bước chân.
   - Có xác suất nhỏ (1% - 2% mỗi giây) giảm tốc về `minSpeedMps` trong vài giây để mô phỏng việc giảm tốc ở ngã tư hoặc khúc cua.
2. **Nội suy tọa độ trên đoạn đường (Segment Interpolation):**
   - Mỗi nhịp 1 giây, tính quãng đường bước đi: `stepDist = currentSpeed * dt`.
   - Di chuyển dọc theo danh sách điểm của OSRM một đoạn đúng bằng `stepDist` (sử dụng công thức Haversine để tính khoảng cách và nội suy tuyến tính tọa độ trên từng segment).
   - Tính góc hướng đi thực tế (`bearing` từ `0°` đến `360°`) giữa điểm trước và điểm hiện tại, cộng thêm nhiễu góc nhỏ `±2.0°`.
3. **Nhiễu vi mô GPS ngang (Lateral Multipath Jitter):**
   - Từ tọa độ chuẩn trên mặt đường OSRM, dịch chuyển nhẹ vuông góc với hướng đi một khoảng ngẫu nhiên mượt từ `-1.2m` đến `+1.2m` để vết GPS không bị thẳng tắp một cách máy móc.
4. **Độ nhấp nhô độ cao (Elevation Undulation):**
   - Tính `altitude` biến thiên mượt theo quãng đường tích lũy bằng tổ hợp sóng:
     `altitude = baseAltitude + sin(accumulatedDist / 120.0) * 2.5 + cos(accumulatedDist / 45.0) * 0.8 + random(-0.15, 0.15)`

### 3.4. Bơm dữ liệu kép (Dual-Provider Mock Location Injection)
Mỗi giây, đóng gói đối tượng `android.location.Location` đầy đủ metadata và bơm đồng thời vào cả 2 luồng:
1. `LocationManager.GPS_PROVIDER` (sử dụng `addTestProvider`, `setTestProviderEnabled`, `setTestProviderLocation`).
2. `FusedLocationProviderClient` (sử dụng `setMockMode(true)`, `setMockLocation(mockLocation)`).
3. **Bắt buộc gán đầy đủ các trường cho `Location`:**
   - `latitude`, `longitude`, `altitude`
   - `speed` (m/s), `bearing` (độ)
   - `accuracy` (biến thiên ngẫu nhiên từ `3.0f` đến `5.5f` mét)
   - `time = System.currentTimeMillis()`
   - `elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()`
   - Với API >= 26: `speedAccuracyMetersPerSecond = 0.2f`, `bearingAccuracyDegrees = 5.0f`, `verticalAccuracyMeters = 2.0f`.

---

## 4. Yêu cầu Hệ thống trên Samsung S24 Ultra (Android 14+)
- Thực thi toàn bộ logic giả lập trong một **Foreground Service** (`MockWalkService`) với khai báo `android:foregroundServiceType="location"`.
- Hiển thị Notification liên tục (Ongoing Notification) cập nhật quãng đường đã đi và tốc độ hiện tại để tránh bị One UI đóng băng tiến trình nền khi người dùng chuyển sang mở màn hình Strava.
- Xử lý đầy đủ Runtime Permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`) và bắt lỗi `SecurityException` (hiển thị hướng dẫn rõ ràng nếu người dùng chưa chọn app trong *Developer Options -> Select mock location app*).

---

## 5. Deliverables
Hãy tạo cấu trúc thư mục và viết đầy đủ code (không dùng comment viết tắt hay placeholder) cho các file:
1. `build.gradle.kts (Module: app)`
2. `AndroidManifest.xml`
3. `OsrmRouteHelper.kt` (Tạo waypoints vòng lặp, gọi OSRM API và parse GeoJSON)
4. `MockWalkService.kt` (Foreground Service, thuật toán di chuyển bám đường, tạo độ nhấp nhô và bơm Mock Location)
5. `activity_main.xml` & `MainActivity.kt` (Bản đồ, UI điều khiển, vẽ Polyline lộ trình, lắng nghe broadcast cập nhật tiến độ từ Service)