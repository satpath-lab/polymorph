<?php
$path = $argv[1];
$OUT = fopen('php://stdout', 'w');
ob_start(function ($b) { fwrite(STDERR, $b); return ''; }, 1);
set_error_handler(function ($no, $str, $file, $line) { throw new ErrorException($str, 0, $no, $file, $line); });
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
