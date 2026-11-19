# OpenTune deneysel Instagram müzik notları

OpenTune çalan parçanın adını ve sanatçısını bu kişisel servise gönderir. Servis Instagram müzik notları kataloğunda arama yapar. Tek bir kayıt eşleşirse o kaydı Yakın Arkadaşlar kitlesine müzik notu olarak göndermeyi dener. Her parçada telefonda işlem yapman gerekmez. Servisin açık ve telefondan erişilebilir kalması gerekir.

## Bir defalık kurulum

1. Kontrol ettiğin Linux veya macOS bilgisayarda Python 3.11+ ile depo kökünden aşağıdaki komutları çalıştır.
2. Girişi kendine ait bir Instagram test hesabıyla yap. Instagram şifresini yalnızca kendi terminaline gir. Oturum ve eşleştirme anahtarı o bilgisayardaki özel `~/.opentune-notes` klasörüne kaydedilir.
3. Telefonun servise ulaşması için kendi HTTPS ters vekilini `127.0.0.1:8765` adresine yönlendir. USB üzerinden denemek için bilgisayarda `adb reverse tcp:8765 tcp:8765` çalıştırıp telefonda `http://127.0.0.1:8765` kullanabilirsin. USB bağlantısı kesildiğinde bu yol da kesilir.
4. OpenTune’da **Ayarlar → Hesap ve entegrasyonlar → Bağlı hizmetler → Otomatik Instagram müzik notları** seçeneğini aç. Servis adresini ve terminalde verilen eşleştirme anahtarını gir. Şarkının en az 10 saniye çalmasını bekle.

```sh
cd services/instagram-notes
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python bridge.py login
python bridge.py serve
```

Telefon yalnızca eşleştirme anahtarını özel uygulama alanında, Android yedeklerinin dışında saklar. Servisi yalnızca kendi cihazında barındır. Anahtarını bilen biri servisin paylaşım işlevini kullanabilir. Anahtarı değiştirmek için servisi durdurup yeniden giriş yap ve OpenTune’u yeni anahtarla bağla. Uygulamadaki anahtarı kapatmak yeni paylaşımları durdurur. Instagram’a gönderilmiş bir not hesabında kalabilir.

## Eşleşme ve doğrulama

Servis, notlar için önerilen kayıtları ve Instagram’ın genel müzik aramasını kullanır. Genel aramada dönen bir parça, o hesapta not olarak paylaşılamayabilir. Servis başlık ve sanatçı alanlarını karşılaştırır, sürüm ve remiks etiketlerini korur. Farklı ses kayıtları aynı ad ve sanatçıyla dönüyorsa paylaşımı atlar. Instagram’ın o hesap ve bölgede sunduğu sonuçlar değişebilir. Var olan kişisel notun görünüyorsa onu korur. Otomatik güncellemeler en az iki dakika arayla yapılır. Zaman aşımında, oturum doğrulaması gerektiğinde veya gönderilen not doğrulanamadığında servisi duraklatır. Instagram hesabını kontrol ettikten sonra servisi yeniden başlatabilirsin.

Meta’nın genel API’sinde müzik notu paylaşımı belgelenmemiştir. Bu servis üçüncü taraf `instagrapi` kullanır. Instagram erişimi kısıtlayabilir, ek doğrulama isteyebilir veya entegrasyonu değiştirebilir. Parçanın yayımlandığına dair en güçlü uygulama içi sonuç, gönderi kimliğinin aynı hesapta müzik türüyle geri okunmasıdır. Dinlenebilirliği doğrulamak için Yakın Arkadaşlar listesindeki ikinci bir hesaptan notu açıp sesi dinlemek gerekir. [Meta paylaşım belgeleri](https://developers.facebook.com/documentation/instagram-platform/content-publishing) · [instagrapi müzik notu desteği](https://github.com/subzeroid/instagrapi/releases/tag/3.2.13)

`python -m unittest discover -v` sahte Instagram istemcisiyle eşleştirmeyi, paylaşım protokolünü ve hata durumlarını sınar. Bu testler gerçek bir Instagram hesabında paylaşım yapmaz.
