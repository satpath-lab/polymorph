MRuby::Build.new do |conf|
  conf.toolchain
  conf.gembox 'stdlib'
  conf.gem core: 'mruby-bin-mrbc'
end
{ 'macos' => ['macosx', 'arm64-apple-macos13'], 'ios' => ['iphoneos', 'arm64-apple-ios15.0'], 'iossim' => ['iphonesimulator', 'arm64-apple-ios15.0-simulator'] }.each do |name, (sdk, target)|
  MRuby::CrossBuild.new("apple-#{name}") do |conf|
    conf.toolchain :clang
    sysroot = `xcrun --sdk #{sdk} --show-sdk-path`.strip
    conf.cc.flags = ['-target', target, '-isysroot', sysroot, '-O2', '-fPIC']
    conf.linker.flags = ['-target', target, '-isysroot', sysroot]
    conf.archiver.command = 'ar'
    %w[stdlib stdlib-ext stdlib-io metaprog].each { |b| conf.gembox b }
    %w[mruby-math mruby-rational mruby-bigint mruby-eval mruby-sprintf mruby-pack mruby-time].each { |g| conf.gem core: g }
  end
end
