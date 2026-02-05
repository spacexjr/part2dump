# Part2Dump

Aplicativo Android (Kotlin) para fazer dump de partições do dispositivo para `/sdcard`, usando root.

## Funcionalidades
- Lista partições em `/dev/block/by-name`.
- Permite selecionar múltiplas partições.
- Executa dump com `dd` via `su`.
- Mostra progresso (usa `status=progress` quando disponível).
- Tela "Sobre" com créditos.

## Requisitos
- Dispositivo Android **rootado** (obrigatório).
- Acesso ao comando `su` e `dd` no dispositivo.
- Espaço livre suficiente no `/sdcard/Backup`.
- caso usar Ksunxt, ksu.. etc permita no manager primeiro antes de abrir o Part2Dump.

## Como buildar

1. Abra o projeto no Android Studio.
2. Aguarde o Gradle sincronizar.
3. Conecte o dispositivo via USB com **Depuração USB** ligada.
4. Clique em **Run** para instalar e executar.

## Uso
1. Toque no menu da toolbar **Atualizar** para listar as partições.
2. Selecione uma ou mais partições.
3. Toque em **Dump selecionado**.
4. Os arquivos serão salvos em `/sdcard/Backup/`.

## Avisos
- O dump de partições pode conter dados sensíveis.
- Use por sua conta e risco.

## Releases
- Em releases 

## Créditos
- spacexjr
