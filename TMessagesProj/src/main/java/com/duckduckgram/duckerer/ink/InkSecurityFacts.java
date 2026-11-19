package com.duckduckgram.duckerer.ink;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * mvs Ink Studio: факты для экрана «Защита».
 *
 * <p>README обещает «факты, а не проценты», поэтому здесь нет оценки «78 %». Каждая
 * проверка — вопрос с ответом «да» или «нет», который приложение действительно
 * может проверить на этом устройстве прямо сейчас. Проверка, ответ на которую
 * неизвестен или неприменим, в счёт не идёт и на датчике не рисуется.
 *
 * <p>То, чего приложение пока не умеет (подпись плагинов, allowlist), сюда не попадает:
 * об этом честно сказано на самом экране, в строках «запланировано».
 */
public final class InkSecurityFacts {

    public static final int OK = 0;
    public static final int WARN = 1;
    /** Не определено или неприменимо: в счёт не идёт. */
    public static final int SKIPPED = 2;

    public static final class Fact {
        public final int titleRes;
        public final int valueRes;
        public final int state;

        Fact(int titleRes, int valueRes, int state) {
            this.titleRes = titleRes;
            this.valueRes = valueRes;
            this.state = state;
        }
    }

    private InkSecurityFacts() {
    }

    public static List<Fact> collect(Context context) {
        List<Fact> facts = new ArrayList<>();

        int signature = signatureState(context);
        facts.add(new Fact(R.string.InkFactSignature,
                signature == OK ? R.string.InkFactSigRelease
                        : signature == WARN ? R.string.InkFactSigDebug : R.string.InkFactUnknown,
                signature));

        boolean passcode = SharedConfig.passcodeHash != null && SharedConfig.passcodeHash.length() > 0;
        facts.add(new Fact(R.string.InkFactPasscode,
                passcode ? R.string.InkFactPasscodeOn : R.string.InkFactPasscodeOff,
                passcode ? OK : WARN));

        // Автоблокировка без кода-пароля бессмысленна, поэтому спрашиваем о ней
        // только когда код есть: иначе один и тот же недостаток считался бы дважды.
        if (passcode) {
            boolean autoLock = SharedConfig.autoLockIn > 0;
            facts.add(new Fact(R.string.InkFactAutoLock,
                    autoLock ? R.string.InkFactAutoLockOn : R.string.InkFactAutoLockOff,
                    autoLock ? OK : WARN));
        } else {
            facts.add(new Fact(R.string.InkFactAutoLock, R.string.InkFactNeedsPasscode, SKIPPED));
        }
        return facts;
    }

    /** Сколько проверок применимо. */
    public static int counted(List<Fact> facts) {
        int count = 0;
        for (Fact fact : facts) {
            if (fact.state != SKIPPED) {
                count++;
            }
        }
        return count;
    }

    /** Сколько применимых проверок пройдено. */
    public static int passed(List<Fact> facts) {
        int count = 0;
        for (Fact fact : facts) {
            if (fact.state == OK) {
                count++;
            }
        }
        return count;
    }

    /**
     * Подписан ли установленный APK отладочным ключом Android.
     *
     * <p>README прямо говорит, что CI подписывает сборку отладочным ключом, пока не
     * заданы релизные секреты, — и такую сборку нельзя ставить поверх сборки с другим
     * ключом. Это настоящий факт о текущей установке, его и показываем.
     */
    @SuppressLint("PackageManagerGetSignatures")
    @SuppressWarnings("deprecation")
    private static int signatureState(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] signatures = info.signatures;
            if (signatures == null || signatures.length == 0) {
                return SKIPPED;
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(signatures[0].toByteArray()));
            String subject = certificate.getSubjectX500Principal().getName();
            return subject.contains("CN=Android Debug") ? WARN : OK;
        } catch (Throwable ignore) {
            return SKIPPED;
        }
    }
}
