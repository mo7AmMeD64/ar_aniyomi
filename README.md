# ar_aniyomi

مستودع إضافات **Aniyomi** عربية — Arabic Aniyomi extension repository.

## التثبيت

افتح Aniyomi ← **Settings ← Browse ← Extension stores** وأضف:

```
https://raw.githubusercontent.com/mo7AmMeD64/ar_aniyomi/repo/index.min.json
```

ثم ثبّت أي إضافة من قسم Extensions.

## الإضافات

| الإضافة | المحتوى |
|---|---|
| CinemaBox | أفلام ومسلسلات وأنمي من cinema.albox.co — سيرفرات MP4 360p–1080p + ترجمة عربية |

## إضافة إضافة جديدة

1. أنشئ مجلداً جديداً `src/<lang>/<name>/` يحتوي `build.gradle.kts` + `res/` + كود Kotlin في `src/`
2. الكود يجب أن يرث من `Source()` (موجود في `core/`)
3. ارفع التغييرات — GitHub Actions يبني الـ APK وينشر فرع `repo` تلقائياً
