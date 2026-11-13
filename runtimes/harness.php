<?php
$path = $argv[1];
$OUT = fopen('php://stdout', 'w');
ob_start(function ($b) { fwrite(STDERR, $b); return ''; }, 1);
set_error_handler(function ($no, $str, $file, $line) { throw new ErrorException($str, 0, $no, $file, $line); });
final class Android {
    static array $mem = [];
    public static function call(string $n, string $m = '', array $a = []) {
        if ($n === 'device') return ['platform' => 'web', 'model' => 'preview', 'sdk' => 0];
        if ($n === 'storage') { if ($m === 'set') { self::$mem[$a['key']] = $a['value'] ?? null; return true; } return $m === 'get' ? (self::$mem[$a['key']] ?? null) : self::$mem; }
        if ($n === 'clipboard') { if ($m === 'set') { self::$mem['_clip'] = $a['text'] ?? ''; return true; } return self::$mem['_clip'] ?? null; }
        return null;
    }
    public static function emit($n, $d = null) {}
    public static function __callStatic($n, $a) { return $a ? self::call($n, $a[0] ?? '', $a[1] ?? []) : new class($n) { public function __construct(private $n) {} public function __call($m, $x) { return Android::call($this->n, $m, []); } }; }
}
function android() { static $p; return $p ??= new class { public function __call($n, $a) { return Android::call($n, $a[0] ?? '', $a[1] ?? []); } }; }
$loadErr = null;
try { (function () use ($path) { require $path; })(); } catch (Throwable $e) { $loadErr = $e; }
while (($line = fgets(STDIN)) !== false) {
    try {
        if ($loadErr) throw $loadErr;
        $req = json_decode($line, true);
        $st = $req['state'] ?? null;
        if ($st === null) $st = function_exists('init') ? init() : [];
        if (($req['event'] ?? null) !== null && function_exists('update')) { $r = update($st, $req['event']); if ($r !== null) $st = $r; }
        $res = ['state' => $st, 'ui' => view($st)];
    } catch (Throwable $e) {
        $res = ['error' => get_class($e) . ': ' . $e->getMessage() . ' (line ' . $e->getLine() . ')'];
    }
    @ob_flush();
    fwrite($OUT, json_encode($res, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PARTIAL_OUTPUT_ON_ERROR) . "\n");
    fflush($OUT);
}
