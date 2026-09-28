# Lock Recorder — Kaise chalayen

Ye ek simple Android app hai jo camera se video record karti hai. Agar aap
recording start karke phone ke side button se screen lock kar dein, to
recording chalti rehti hai (kyunke ye Foreground Service ke andar chal rahi
hoti hai, sirf screen k Activity mein nahi). Phone unlock kar ke app dobara
kholein aur "Stop Recording" dabayein to recording ruk kar file save ho
jayegi.

## Chalane ka tareeqa

1. **Android Studio** (latest version) install karein.
2. Is poore `LockRecorder` folder ko Android Studio mein **Open** karein
   (File → Open → is folder ko select karein).
3. Gradle sync hone dein (pehli dafa thora time lagega, internet chahiye
   hoga dependencies download karne ke liye).
4. Apna Android phone USB se connect karein, ya ek Emulator banayein
   (minimum Android 8.0 / API 26).
5. Green "Run" button dabayein.

## App use karna

1. App khulte hi Camera aur Microphone ki permission maangega — Allow karein.
2. "Start Recording" dabayein. Ek notification bar mein "Recording video"
   dikhega.
3. Ab side button se screen lock kar dein — recording chalti rahegi.
4. Jab record khatam karni ho, phone unlock karein, app khol lein
   (ya notification par tap kar lein), aur "Stop Recording" dabayein.
5. Video file phone ki storage mein yahan save hoti hai:
   `Android/data/com.example.lockrecorder/files/REC_<date>-<time>.mp4`

## Zaroori note

- Kuch phones (Xiaomi/MIUI, Oppo, Vivo jaise "aggressive battery saver"
  waale) apps ko background mein band kar dete hain. Agar recording
  screen lock hone par ruk jaye, to phone ki Settings mein jaake is app
  ke liye:
  - "Battery saver" se exclude karein / "No restrictions" set karein
  - "Autostart" ya "Background activity" allow karein
- Video quality abhi HD (720p) par set hai — `RecordingService.kt` file
  mein `Quality.HD` ko `Quality.FHD` ya `Quality.SD` mein badal sakte hain.
- Agar aap chahte hain ke recording tap se front camera se ho, to
  `CameraSelector.DEFAULT_BACK_CAMERA` ko `DEFAULT_FRONT_CAMERA` kar dein.
