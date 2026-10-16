# 17 — Step into e step return na UI

Status: ready-for-agent
Blocked by: 10, 11, 14

## O que construir

- Step into (F5) em Step into elegível abre o editor da Rotina chamada (Fonte do banco) e pausa no primeiro statement; a view Debug mostra a pilha de frames.
- Step return (F7) executa até sair do frame atual.
- Step into em linha com chamada Executada no servidor faz step over e a linha mostra o aviso "executado no servidor" (anotação no editor).
- Breakpoints na Rotina chamada só valem se o editor dela estiver aberto (consequência de breakpoints não persistentes).

## Critérios de aceite

- [ ] Cadeia A → B → C: step into até C, step return volta a B na linha seguinte à chamada.
- [ ] Selecionar um frame antigo na view Debug mostra as variáveis dele em Variables.
- [ ] `INSERT … SELECT F(x)` mostra o aviso e não pausa em `F`.
- [ ] Step into em sub-rotina interna funciona igual.
