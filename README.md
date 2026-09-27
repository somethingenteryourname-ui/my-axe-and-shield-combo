# AegisTitan (Minecraft 1.21.11 Paper plugin)

**Aegis Wall Shield** – `/getshield`
Raise it and a giant energy wall shaped like a shield appears where you look.
Blocks all damage and projectiles coming through it (arrows bounce back), nobody can
walk through it, it's unbreakable, and normal axes can't disable it.
`/shieldsize <width> <height>` or `/shieldsize small|medium|large|huge|massive`

**Titan Cleaver** – `/getaxe`
Netherite axe: Sharpness V, Efficiency V, Unbreaking III, Mending, Fire Aspect II.
Sneak + right click = Titan Slam: a colossal particle axe smashes the ground,
splits it open and sends out a shockwave. Any Aegis Wall near the impact is cut in
half and that player's shield is disabled for a few seconds.

Settings are in `plugins/AegisTitan/config.yml` (`/aegistitan reload`).

## Building
Push this repo to GitHub → Actions tab → latest "Build AegisTitan" run →
download the `AegisTitan-plugin` artifact → unzip → put the .jar in `plugins/`.
