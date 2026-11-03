# BarkodPRO Terminal: səs düymələrini WebView-ə ötürür (kamera açmaq üçün).
# GitHub Actions-da "npx cap sync android"-dən SONRA, gradle build-dən ƏVVƏL işə salın.
# MainActivity-dəki mövcud kodu (məs. FTP plugin qeydiyyatı) silmir, yalnız əlavə edir.
import glob, sys

fl = glob.glob('android/app/src/main/java/**/MainActivity.java', recursive=True)
if not fl:
    sys.exit('MainActivity.java tapılmadı')
p = fl[0]
s = open(p, encoding='utf-8').read()
if 'volDuyme' in s:
    print('Artıq əlavə olunub:', p); sys.exit(0)

imports = 'import android.view.KeyEvent;\nimport android.media.AudioManager;\n'
i = s.index(';', s.index('package')) + 1
s = s[:i] + '\n\n' + imports + s[i:]

metod = '''
  // Səs düyməsi: əvvəl JS-dən soruşur (window.volDuyme). JS "1" qaytarsa kamera üçün istifadə olunub,
  // əks halda səs adi qaydada artır/azalır.
  @Override
  public boolean dispatchKeyEvent(KeyEvent e) {
    int k = e.getKeyCode();
    if ((k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP)
        && getBridge() != null && getBridge().getWebView() != null) {
      if (e.getAction() == KeyEvent.ACTION_DOWN) {
        final boolean up = k == KeyEvent.KEYCODE_VOLUME_UP;
        String js = "(window.volDuyme?window.volDuyme('" + (up ? "up" : "down") + "'," + (e.getRepeatCount() > 0) + "):'0')";
        getBridge().getWebView().evaluateJavascript(js, v -> {
          if (v == null || !v.contains("1")) sesDeyis(up);
        });
      }
      return true;
    }
    return super.dispatchKeyEvent(e);
  }

  private void sesDeyis(boolean up) {
    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
    if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
        up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
  }
'''
j = s.rstrip().rfind('}')
s = s[:j] + metod + '}\n'
open(p, 'w', encoding='utf-8').write(s)
print('Səs düyməsi əlavə olundu:', p)
