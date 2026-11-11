MRuby::Build.new do |conf|
  conf.toolchain
  conf.gembox 'stdlib'
  conf.gem core: 'mruby-bin-mrbc'
end
bin = File.join(ENV['ANDROID_NDK_HOME'], 'toolchains/llvm/prebuilt/darwin-x86_64/bin')
{ 'arm64-v8a' => 'aarch64-linux-android24', 'x86_64' => 'x86_64-linux-android24' }.each do |abi, triple|
  MRuby::CrossBuild.new("android-#{abi}") do |conf|
    conf.toolchain :clang
    conf.cc.command = "#{bin}/#{triple}-clang"
    conf.cc.flags = ['-fPIC', '-O2', '-ffunction-sections', '-fdata-sections']
    conf.linker.command = "#{bin}/#{triple}-clang"
    conf.archiver.command = "#{bin}/llvm-ar"
    %w[stdlib stdlib-ext stdlib-io metaprog].each { |b| conf.gembox b }
    %w[mruby-math mruby-rational mruby-bigint mruby-eval].each { |g| conf.gem core: g }
    conf.gem core: 'mruby-sprintf'
    conf.gem core: 'mruby-pack'
    conf.gem core: 'mruby-time'
  end
end
