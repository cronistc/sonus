# Sonus — código no celular (LEIA-ME)

Esta pasta (`Documents/Sonus-codigo/`) tem tudo para o Claude do celular continuar o Sonus sozinho:

- `sonus.bundle` — o repositório git inteiro (histórico completo). Restaurar: `git clone sonus.bundle sonus`
- `sonus/` — a árvore de arquivos da versão publicada (para ler sem git)
- `Sonus-chave/` — **chave de assinatura** (`sonus.jks` + `sonus.properties`). SEM ela os celulares não aceitam
  a atualização. Nunca publique, nunca faça commit dela.
- `VERSAO.txt` — versão e código publicados (o próximo código tem que ser MAIOR).

## Compilar no Termux (Ubuntu via proot-distro)

```
pkg install proot-distro && proot-distro install ubuntu && proot-distro login ubuntu
apt update && apt install -y openjdk-17-jdk git unzip wget python3
# Android SDK (linha de comando)
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && unzip commandlinetools-*.zip && mv cmdline-tools latest
export ANDROID_HOME=~/android-sdk PATH=$PATH:~/android-sdk/cmdline-tools/latest/bin
yes | sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

**aapt2 no ARM:** o aapt2 que o Gradle baixa é x86_64 e não roda no celular. Use o aapt2 ARM do pacote do Termux
(`pkg install aapt2` fora do Ubuntu) e aponte o Gradle para ele em `android/gradle.properties`:
`android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`

Depois:
```
cd sonus/android
echo "sdk.dir=$HOME/android-sdk" > local.properties
cp -r /sdcard/Documents/Sonus-codigo/Sonus-chave ~/Sonus-chave
./gradlew clean assembleDebug          # teste
../tools/publicar.sh 0.1.4 9 ../docs/novidades-0.1.4.txt   # publicar (precisa do acesso ao GitHub)
```

## Regras do projeto (resumo)
- Interface em `prototype/` (HTML/JS/CSS dentro de um WebView); parte nativa em `android/` (Kotlin).
- Motor de busca/áudio: NewPipe Extractor (jitpack). Se o YouTube mudar e quebrar, veja `docs/SONUSPIPE-GUIA.md`.
- Testes: `tools/estresse/bateria.py <serial>` (só dentro do app). Nunca mexer em configurações do sistema.
- O visual é o produto: nada muda no visual sem o dono pedir.
