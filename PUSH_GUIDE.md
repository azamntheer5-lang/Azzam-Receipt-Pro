# 🚀 دليل رفع المشروع إلى GitHub

## ✅ الحالة الحالية للمشروع (مهيّأ بالكامل)

```
المسار:          /home/z/my-project/analysis/Azzam-Whatsapp--main/
المستودع:        git محلي مهيّأ
الفرع:           main
آخر commit:      44e49a0 feat: complete project migration and standalone pro version setup
الـ remote:      https://github.com/azamntheer5-lang/Azzam-Receipt-Pro.git
الحالة:          working tree clean (جاهز للـ push)
```

كل ما تبقّى هو تنفيذ **أمر واحد** من جهازك بعد إعداد المصادقة.

---

## 📋 الخطوة 1: نقل المشروع إلى جهازك

حمّل مجلد `Azzam-Whatsapp--main` بالكامل إلى جهازك (كـ ZIP أو بأي طريقة متاحة في واجهة المعاينة). ثم فك الضغط في مكان مناسب.

---

## 🔐 الخطوة 2: إعداد المصادقة مع GitHub (اختر طريقة واحدة)

### الطريقة A: Personal Access Token (الأسهل للمبتدئين)

1. سجّل الدخول إلى GitHub باسم المستخدم `azamntheer5-lang`
2. اذهب إلى: **Settings → Developer settings → Personal access tokens → Tokens (classic)**
3. اضغط **Generate new token (classic)**
4. املأ:
   - **Note**: `Receipt Scanner Pro`
   - **Expiration**: 90 days (أو حسب رغبتك)
   - **Scopes**: فعّل `repo` (كامل)
5. اضغط **Generate token** وانسخ الـ token فوراً (لن يظهر مرة أخرى)

### الطريقة B: GitHub CLI (الأسرع)

إن كان `gh` مُثبَّتاً على جهازك:
```bash
gh auth login
# اختر: GitHub.com → HTTPS → Login with a web browser
```

### الطريقة C: مفاتيح SSH (للاستخدام طويل المدى)

```bash
# إنشاء مفتاح
ssh-keygen -t ed25519 -C "your_email@example.com"
# اضغط Enter لكل الأسئلة (الافتراضي مقبول)

# عرض المفتاح العام لنسخه
cat ~/.ssh/id_ed25519.pub
```
ثم في GitHub: **Settings → SSH and GPG keys → New SSH key** والصق المفتاح.

---

## 🚀 الخطوة 3: تنفيذ الـ Push

افتح Terminal في مجلد المشروع (`Azzam-Whatsapp--main`) ونفّذ:

```bash
# تحقق أن الـ remote صحيح
git remote -v
# يجب أن ترى:
# origin  https://github.com/azamntheer5-lang/Azzam-Receipt-Pro.git

# تحقق أن الـ commit موجود
git log --oneline -1
# يجب أن ترى:
# 44e49a0 feat: complete project migration and standalone pro version setup

# ادفع!
git push -u origin main
```

### عند الطلب (إن استخدمت الطريقة A):
- **Username**: `azamntheer5-lang`
- **Password**: الصق الـ Personal Access Token (وليس كلمة مرور GitHub)

### عند الطلب (إن استخدمت الطريقة C):
لا حاجة لإدخال أي شيء — افتح أولاً ملف `.git/config` أو نفّذ:
```bash
git remote set-url origin git@github.com:azamntheer5-lang/Azzam-Receipt-Pro.git
git push -u origin main
```

---

## ✅ علامات النجاح

عند نجاح الـ push سترى شيئاً مثل:
```
Enumerating objects: 120, done.
Counting objects: 100% (120/120), done.
Writing objects: 100% (120/120), 285.67 KiB | 7.34 MiB/s, done.
To https://github.com/azamntheer5-lang/Azzam-Receipt-Pro.git
 * [new branch]      main -> main
branch 'main' set up to track 'origin/main'.
```

---

## 🤖 الخطوة 4 (اختياري): تفعيل GitHub Actions لبناء APK تلقائياً

المشروع يحتوي على `.github/workflows/build-apk.yml` جاهز. بعد الـ push الأول:

1. اذهب للمستودع على GitHub: `https://github.com/azamntheer5-lang/Azzam-Receipt-Pro`
2. تبويب **Actions**
3. **Settings → Actions → General → Allow all actions**
4. **Workflow permissions → Read and write permissions** (لإنشاء Release تلقائياً)
5. عند كل `git push`، سيُبنى APK تلقائياً ويُنشر كـ Release تحت تبويب Releases

---

## 🔧 استكشاف الأخطاء الشائعة

### خطأ: `fatal: Authentication failed`
- تأكد أن الـ token لم ينتهِ صلاحيته
- تأكد أنك فعّلت صلاحية `repo` عند إنشائه
- تأكد أن اسم المستخدم `azamntheer5-lang` (دقيق، حروف كبيرة/صغيرة مهمة)

### خطأ: `fatal: Repository not found`
- تأكد أن المستودع `Azzam-Receipt-Pro` موجود فعلاً على حسابك
- إن كان private، تأكد أن الـ token له صلاحية `repo`

### خطأ: `Permission denied (publickey)` (SSH)
- تأكد أنك أضفت المفتاح العام في GitHub Settings
- جرّب: `ssh -T git@github.com` (يجب أن ترى رسالة ترحيب)

### خطأ: `Updates were rejected` (للمستودعات غير الفارغة)
```bash
git pull origin main --rebase
git push -u origin main
```

---

## 📞 للتحقق السريع

بعد الـ push، افتح المتصفح على:
```
https://github.com/azamntheer5-lang/Azzam-Receipt-Pro
```

ستجد المشروع كاملاً مع كل الملفات والـ commit:
```
feat: complete project migration and standalone pro version setup
```

---

## 📊 ملخص ما ستراه على GitHub

| العنصر | القيمة |
|---|---|
| اسم المستخدم | `azamntheer5-lang` |
| اسم المستودع | `Azzam-Receipt-Pro` |
| الرابط الكامل | `https://github.com/azamntheer5-lang/Azzam-Receipt-Pro` |
| عدد الملفات | 93 ملفاً |
| اسم التطبيق | Receipt Scanner Pro |
| ApplicationId | `com.example.receiptscanner.pro` |
| الحزمة الداخلية | `com.azzam.receiptscanner` |

---

## ⚠️ ملاحظة أمنية أخيرة

- ✅ المشروع **لا يحتوي** على أي مفاتيح API أو كلمات مرور في الكود
- ✅ مفاتيح API تُدخل من واجهة الإعدادات وتُخزَّن مشفّرة على الجهاز فقط
- ✅ لا يوجد `local.properties` أو `.keystore` في الملفات المرفوعة
- ✅ `.gitignore` يغطي كل الملفات الحساسة

**آمن للرفع كمستودع public أو private.**

---

## 🎯 ملخص نهائي

```bash
# الأوامر الثلاثة الأساسية (من جهازك):
cd /path/to/Azzam-Whatsapp--main
git remote -v   # تحقق
git push -u origin main   # الرفع!
```

**هذا كل ما تحتاجه. المشروع مهيّأ 100% وجاهز.** 🚀
