# Tek telefonda Instagram müzik notu denemesi

1.1.4 ön sürümünde yalnızca OpenTune APK’sını kurman yeterli. Deneme Android 9 ve üzeri ile güncel Android System WebView gerektirir. Bir bilgisayar, Termux veya servis adresi gerekmez. Denemede Instagram’ın web arayüzü kullanılır. Hesabının web gelen kutusunda müzikli not oluşturma desteği bulunması gerekir. Bu akış canlı hesaplarla henüz doğrulanmadı.

1. Instagram’da deneyeceğin yan hesabın **Yakın Arkadaşlar** listesine, notu dinleyebilecek ikinci hesabı ekle.
2. OpenTune’da bir şarkı çal. **Ayarlar → Hesap ve entegrasyonlar → Bağlı hizmetler → Telefonda müzik notu denemesi** seçeneğini aç. Yan hesabının kullanıcı adını yaz ve **Instagram’ı burada aç** düğmesine dokun.
3. OpenTune içindeki Instagram sayfasında yan hesabına giriş yap. Instagram ek doğrulama isterse kendi sayfasında tamamla. Gelen kutusu açıkken, şarkı en az 10 saniyedir çalıyorsa **Çalan parçayı dene** düğmesine dokun. OpenTune yeni not ekranını açıp müzik kataloğunda başlık ve sanatçıyı aramayı dener.
4. Aynı kaydın tek bir sonucu bulunursa önizlemeyi incele. Gerekirse Instagram’da kitleyi **Yakın Arkadaşlar** seçip **Kitleyi kontrol et** düğmesine dokun. Uygulama hesap, parça ve kitleyi gösterdiğinde **Bir kez paylaş** seçeneği bir paylaşım denemesi yapar.
5. İkinci hesapla Instagram gelen kutusunu aç. Notu görüp doğru parçayı dinleyebiliyorsan canlı test tamamlanmıştır. Uygulama yalnızca Paylaş düğmesine basıldığını raporlayabilir. Sonucu aktarırken durum mesajı yeterli. Şifre, doğrulama kodu veya oturum bilgileri yalnızca Instagram’ın kendi giriş sayfasında kullanılmalıdır.

OpenTune giriş formunu okumaz. Deneme ayrı bir WebView veri dizini kullanır. Bu dizin Android yedeklerinin dışında tutulur. Kapatırken ve bir sonraki denemeden önce bu oturumun çerezleri temizlenir. Deneme ekranı 15 dakika sonra kapanır. Ekran arka plana giderse veya şarkı değişirse bekleyen işlem durur. Sürekli arka plan paylaşımı bu ön sürümdeki telefon denemesinin kapsamı dışındadır.

**Doğrulanamadı** mesajı, hesabın kimliği, yeni not düğmesi, müzik seçimi veya hedef kitle gibi bir adımın görünür arayüzden güvenilir biçimde tanınamadığını gösterir. Uygulama Türkçe ve İngilizce etiketleri tanır. Instagram yalnızca kendi mobil uygulamasında bu özelliği sunuyorsa web denemesi o hesapta ilerleyemez.
