require "mysql2/version"

module Mysql2
  # makes `gem "mysql2", "~> 0.5"` (e.g. ActiveRecord's mysql2_adapter.rb)
  # resolve to this gem even though its installed under different name,
  # e.g. in Gemfile:
  #
  #   `gem "mysql2-jruby", require: "mysql2/compat"`
  #
  module Compat
    GEM_NAME = "mysql2".freeze
    class << self
      def register_gem_alias
        bundler? ? register_with_bundler : register_with_rubygems
      end

      private

      def bundler?
        (defined?(Bundler) && Bundler.rubygems) rescue false
      end

      def register_with_bundler
        specs = Bundler.load.specs.to_a
        return if specs.any? { |s| s.name == GEM_NAME }

        # rebuild Bundler (the specs_by_name its `Kernel#gem` patch checks)
        #Bundler.rubygems.replace_entrypoints(specs + [alias_spec])

        # re-patch Bundler's `Kernel#gem` only with alias added to the map;
        # must keep every current gem (incl. default gems) or `gem` rejects them
        specs_by_name = Bundler.rubygems.add_default_gems_to(specs)
        specs_by_name[GEM_NAME] = alias_spec
        Bundler.rubygems.replace_gem(specs_by_name)
      end

      def register_with_rubygems
        return if Gem.loaded_specs.key?(GEM_NAME)

        spec = alias_spec
        spec.activated = true
        Gem.loaded_specs[GEM_NAME] = spec
      end

      def alias_spec
        spec = Gem::Specification.new do |s|
          s.name = GEM_NAME
          s.version = Mysql2::VERSION
          s.require_paths = ["lib"]
        end
        spec.full_gem_path = File.expand_path("../..", __dir__)
        spec
      end
    end
  end
end

Mysql2::Compat.register_gem_alias

require "mysql2"
