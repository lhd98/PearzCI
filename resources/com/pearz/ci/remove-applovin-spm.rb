require 'xcodeproj'
project = Xcodeproj::Project.open(ARGV.fetch(0))
packages = project.root_object.package_references.select do |reference|
  reference.isa == 'XCRemoteSwiftPackageReference' &&
    reference.repositoryURL.to_s.downcase.include?('applovin-max-swift-package')
end
products = project.objects.select do |object|
  object.isa == 'XCSwiftPackageProductDependency' &&
    packages.include?(object.package)
end
project.targets.each do |target|
  next unless target.respond_to?(:package_product_dependencies)
  products.each { |product| target.package_product_dependencies.delete(product) }
end
products.each(&:remove_from_project)
packages.each(&:remove_from_project)
project.save
