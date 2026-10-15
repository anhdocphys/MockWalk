# MockWalk - Android Strava Realistic Walk Simulator

Ứng dụng Android (Kotlin) giả lập chuyển động đi bộ tự nhiên bám sát đường xá thực tế thông qua **OSRM Foot Routing API** và bơm dữ liệu GPS giả lập kép (**Dual-Provider Mock Location**) vào hệ thống, giúp **Strava** ghi nhận thành một buổi đi bộ (Walk) hợp lệ, tự nhiên với đầy đủ biểu đồ nhịp độ (Pace) và độ cao (Elevation).

Được tối ưu đặc biệt cho **Samsung Galaxy S24 Ultra** chạy **Android 14/15 (One UI, Target SDK 34)**.

---

## 🌟 Tính Năng Nổi Bật

1. **Bản đồ Google Maps tương tác:**
   - Chọn điểm xuất phát bằng cách chạm vào bản đồ.
   - Vẽ lộ trình Polyline thực tế bám sát vỉa hè/lòng đường từ OSRM.
   - Hiển thị vị trí người đi bộ và xoay góc hướng đi theo thời gian thực.

2. **Tự động sinh lộ trình qua OSRM Foot Routing (Không cần API Key):**
   - Tự động sinh chuỗi Waypoints dạng vòng lặp (loop) quanh điểm xuất phát dựa trên `Target Distance`.
   - Gọi OSRM public API (`https://router.project-osrm.org/route/v1/foot/...`).
   - Tự động nối tiếp / lặp vòng lộ trình nếu chiều dài đường ngắn hơn mục tiêu.
   - Cơ chế fallback thông minh tự động nội suy nếu mạng hoặc server OSRM gặp sự cố.

3. **Thuật toán Giả lập Động học & Chống lọc Strava (Strava Anti-Filter):**
   - **Vòng lặp thời gian thực:** Cập nhật mỗi `~1000ms` với sai số ngẫu nhiên `±30ms` (`850ms - 1030ms`).
   - **Inertial Speed Random Walk:** Biến thiên gia tốc ngẫu nhiên `[-0.12, +0.12] m/s²`, kết hợp sóng nhịp bước chân (`cadenceWave ~ 1.8 Hz`).
   - **Tạm dừng ngã tư / khúc cua:** Xác suất 1.5% mỗi giây tự động giảm tốc về `minSpeed` trong 2 - 4 giây.
   - **Nhiễu vi mô GPS ngang (Lateral Multipath Jitter):** Dịch chuyển nhẹ vuông góc với hướng đi `[-1.2m, +1.2m]` có lọc thông thấp để vết GPS không bị thẳng tắp như máy móc.
   - **Độ nhấp nhô độ cao (Elevation Undulation):** Mô phỏng độ cao bằng tổ hợp hàm sóng kết hợp nhiễu ngẫu nhiên.

4. **Bơm dữ liệu kép (Dual-Provider Mock Location Injection):**
   - Bơm đồng thời vào `LocationManager.GPS_PROVIDER` và `FusedLocationProviderClient`.
   - Cung cấp đầy đủ metadata: `latitude`, `longitude`, `altitude`, `speed`, `bearing`, `accuracy` (3.0m - 5.5m), `time`, `elapsedRealtimeNanos`.
   - Hỗ trợ các trường độ chính xác mở rộng trên Android 8+ (API 26+): `speedAccuracyMetersPerSecond`, `bearingAccuracyDegrees`, `verticalAccuracyMeters`.

5. **Tương thích Android 14+ (Samsung S24 Ultra & One UI):**
   - Chạy toàn bộ tiến trình trong **Foreground Service** với type `location`.
   - Giữ **Partial WakeLock** và hiển thị **Ongoing Notification** liên tục cập nhật quãng đường và tốc độ, ngăn chặn hệ thống One UI đóng băng hoặc hủy tiến trình nền khi người dùng mở Strava.

---

## 📱 Hướng Dẫn Cài Đặt Trên Samsung Galaxy S24 Ultra

### Bước 1: Bật Tùy chọn cho nhà phát triển (Developer Options)
1. Mở **Cài đặt (Settings)** > **Thông tin điện thoại (About phone)** > **Thông tin phần mềm (Software information)**.
2. Chạm 7 lần liên tục vào **Số hiệu bản tạo (Build number)** cho đến khi máy báo đã bật chế độ nhà phát triển.

### Bước 2: Chọn ứng dụng vị trí giả lập (Select Mock Location App)
1. Quay lại **Cài đặt (Settings)** > cuộn xuống dưới cùng chọn **Cài đặt cho người phát triển (Developer options)**.
2. Tìm mục **Chọn ứng dụng vị trí giả (Select mock location app)**.
3. Chọn **MockWalk** (hoặc `MockWalk (Debug)`).

### Bước 3: Cấp quyền thông báo & vị trí
Khi mở app lần đầu, chấp nhận cấp quyền:
- **Thông báo (Notification)** để duy trì Foreground Service.
- **Vị trí (Location)** để tải vị trí ban đầu trên Google Maps.

---

## 🏃 Cách Sử Dụng Cùng Ứng Dụng Strava

1. **Mở MockWalk**:
   - Chạm vào bản đồ để chọn **Điểm xuất phát**.
   - Điều chỉnh các thông số mong muốn:
     - `Min Speed (km/h)`: Mặc định `4.0`
     - `Max Speed (km/h)`: Mặc định `6.0`
     - `Target Distance (km)`: Mặc định `3.0`
   - Bấm nút **Start Simulation**.
   - Lộ trình sẽ được vẽ trên bản đồ và tiến trình giả lập GPS bắt đầu chạy ngầm.
2. **Mở Strava**:
   - Chuyển sang ứng dụng **Strava** > bấm tab **Ghi lại (Record)** > chọn môn **Đi bộ (Walk)**.
   - Strava sẽ bắt tín hiệu GPS giả lập từ MockWalk với độ chính xác cao.
   - Bấm **Start** trên Strava để ghi hoạt động.
3. **Theo dõi**:
   - Bạn có thể khóa màn hình hoặc chuyển qua lại giữa các ứng dụng. Notification trên thanh trạng thái sẽ cập nhật thời gian thực quãng đường và tốc độ.
   - Khi hoàn thành quãng đường mục tiêu, MockWalk sẽ tự động dừng. Trên Strava, bạn bấm **Finish** và lưu hoạt động như một buổi đi bộ thực tế.

---

## 🏗️ Cấu Trúc Dự Án

```
MockWalk/
├── app/
│   ├── build.gradle.kts                # Cấu hình Gradle, SDK 34, dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml         # Khai báo Foreground Service & Permissions
│   │   ├── java/com/mockwalk/app/
│   │   │   ├── MockWalkApplication.kt  # Khởi tạo Notification Channel
│   │   │   ├── data/
│   │   │   │   └── OsrmRouteHelper.kt  # Sinh Waypoints vòng lặp, gọi & parse OSRM API
│   │   │   ├── service/
│   │   │   │   └── MockWalkService.kt  # Foreground Service, động học & bơm Mock GPS
│   │   │   ├── ui/
│   │   │   │   └── MainActivity.kt     # Google Maps UI, điều khiển & lắng nghe tiến trình
│   │   │   └── util/
│   │   │       └── GeoUtils.kt         # Haversine, Bearing, nội suy và dịch chuyển ngang
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/
│   │       │   ├── colors.xml
│   │       │   ├── strings.xml
│   │       │   └── themes.xml
│   │       └── drawable/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 🔑 Lưu Ý Về Google Maps API Key
Trong file `app/src/main/res/values/strings.xml`, trường `google_maps_key` đã được tạo sẵn. Bạn có thể thay bằng Google Maps API Key cá nhân của mình để hiển thị bản đồ đầy đủ:
```xml
<string name="google_maps_key">YOUR_GOOGLE_MAPS_API_KEY</string>
```
*(Ngay cả khi chưa thêm API Key, toàn bộ tiến trình OSRM, tính toán động học và bơm Mock Location vào hệ thống cho Strava vẫn hoạt động 100% độc lập).*
