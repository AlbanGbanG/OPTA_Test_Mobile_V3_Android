# OPTA Test Mobile V3 — V10.1 Gems 3100R

L'application Android communique avec le firmware OPTA V10.1 (`OPTA-V10` en BLE,
UUID de service `7A9A0001-6E0A-4DA1-9D8B-3A4E00000001`). Les capteurs sont
**Gems 3100R, 0–5 V correspondant à 0–10 bar** : I1 = entrée d'air, I2 = élément testé.

Le tableau de bord affiche les deux pressions et les états des relais D0 (purge)
et D1 (air), permet START avec confirmation/code, STOP/purge sans code et
acquittement sous conditions. L'onglet Paramètres demande le code à six chiffres
initialement défini sur le port USB de l'OPTA (`CODE SET 123456`, remplacer
123456 par un code propre à l'installation). Les pressions sont en bar, y
compris la limite de sécurité qui ne peut excéder 10,00 bar.

L'onglet Essais récupère les 50 dernières fiches conservées par l'OPTA,
les mémorise sur le téléphone et permet un export CSV. Il ne garantit pas
l'historique intégral si les anciennes fiches sont écrasées sur l'OPTA avant
synchronisation : effectuer des exports réguliers.

## Compiler l'APK

Le dossier `.github/workflows/build-apk.yml` du pack utilise GitHub Actions.
Copier le **dossier Android V3** et `.github` à la racine du dépôt, lancer
le workflow `Construire APK OPTA V3` et récupérer `app-debug.apk` dans l'artifact.
Aucun APK n'a été compilé dans cette archive.

## Sécurité

L'authentification à six chiffres n'est pas une fonction de sécurité câblée ;
BLE non chiffré peut exposer le code. Valider les risques et les fonctions
sécurité pneumatiques avant toute mise en service.

Voir `../LISEZ_MOI.md` pour le câblage et la réception.
